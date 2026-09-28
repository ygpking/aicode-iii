package main

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"
)

// ---------------- OpenAI Chat 兼容端点 ----------------

func (g *Gateway) handleChat(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, jsonObj{"error": jsonObj{"message": "method not allowed"}})
		return
	}
	if err := g.limiter.acquire(); err != nil {
		writeJSON(w, 503, jsonObj{"error": jsonObj{"message": err.Error(), "type": "overloaded_error"}})
		return
	}
	defer g.limiter.release()
	appName, err := g.authorize(r)
	if err != nil {
		writeJSON(w, 401, jsonObj{"error": jsonObj{"message": err.Error(), "type": "invalid_request_error"}})
		return
	}
	raw, err := readBody(r)
	if err != nil {
		status, msg := bodyErrorPayload(err)
		logRejectedBody(r, "openai-chat", status, msg)
		writeJSON(w, status, jsonObj{"error": jsonObj{"message": msg, "type": "invalid_request_error"}})
		return
	}
	var body jsonObj
	if err := json.Unmarshal(raw, &body); err != nil {
		// 带上体量与解析错误位置：压缩上下文超限时，这两条是判断「是体积还是格式」的关键。
		logRejectedBody(r, "openai-chat", 400, "请求体不是合法 JSON: "+err.Error())
		writeJSON(w, 400, jsonObj{"error": jsonObj{
			"message": fmt.Sprintf("请求体不是合法 JSON（收到 %d 字节）: %v", len(raw), err),
			"type":    "invalid_request_error",
		}})
		return
	}
	g.serveOpenAI(w, appName, "openai-chat", body, raw)
}

func (g *Gateway) handleResponses(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, jsonObj{"error": jsonObj{"message": "method not allowed"}})
		return
	}
	if err := g.limiter.acquire(); err != nil {
		writeJSON(w, 503, jsonObj{"error": jsonObj{"message": err.Error(), "type": "overloaded_error"}})
		return
	}
	defer g.limiter.release()
	appName, err := g.authorize(r)
	if err != nil {
		writeJSON(w, 401, jsonObj{"error": jsonObj{"message": err.Error()}})
		return
	}
	raw, err := readBody(r)
	if err != nil {
		status, msg := bodyErrorPayload(err)
		logRejectedBody(r, "openai-responses", status, msg)
		writeJSON(w, status, jsonObj{"error": jsonObj{"message": msg}})
		return
	}
	var body jsonObj
	if err := json.Unmarshal(raw, &body); err != nil {
		logRejectedBody(r, "openai-responses", 400, "请求体不是合法 JSON: "+err.Error())
		writeJSON(w, 400, jsonObj{"error": jsonObj{
			"message": fmt.Sprintf("请求体不是合法 JSON（收到 %d 字节）: %v", len(raw), err),
		}})
		return
	}
	chat := responsesToChat(body)
	g.serveOpenAI(w, appName, "openai-responses", chat, raw)
}

func (g *Gateway) handleMessages(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		writeJSON(w, 405, jsonObj{"error": jsonObj{"message": "method not allowed"}})
		return
	}
	if err := g.limiter.acquire(); err != nil {
		writeJSON(w, 503, jsonObj{"type": "error", "error": jsonObj{"type": "overloaded_error", "message": err.Error()}})
		return
	}
	defer g.limiter.release()
	appName, err := g.authorize(r)
	if err != nil {
		writeJSON(w, 401, jsonObj{"error": jsonObj{"type": "error", "error": jsonObj{"type": "authentication_error", "message": err.Error()}}})
		return
	}
	raw, err := readBody(r)
	if err != nil {
		status, msg := bodyErrorPayload(err)
		logRejectedBody(r, "anthropic-messages", status, msg)
		writeJSON(w, status, jsonObj{"type": "error", "error": jsonObj{"type": "invalid_request_error", "message": msg}})
		return
	}
	var body jsonObj
	if err := json.Unmarshal(raw, &body); err != nil {
		logRejectedBody(r, "anthropic-messages", 400, "请求体不是合法 JSON: "+err.Error())
		writeJSON(w, 400, jsonObj{"type": "error", "error": jsonObj{
			"type":    "invalid_request_error",
			"message": fmt.Sprintf("请求体不是合法 JSON（收到 %d 字节）: %v", len(raw), err),
		}})
		return
	}
	chat := anthropicToChat(body)
	g.serveAnthropic(w, appName, chat, raw)
}

// serveOpenAI 处理 OpenAI Chat / Responses 两种入站协议的流式转发。
// capMaxTokens 按模型目录里的上限裁剪输出 token 数。
//
// 客户端常发超过模型上限的值（Claude Code 默认 32000+），不裁剪会被上游直接拒。
// 三个字段都要管：OpenAI 老客户端发 max_tokens，Responses 系发 max_completion_tokens，
// 而 max_output_tokens 是入站 Responses 协议的原名（在协议转换里才改名为 max_tokens）
// —— 只裁其中一个会漏。数字经 JSON 反序列化后是 float64，只裁显式给的正数，
// 其余（null/字符串）交给上游自己判。目录未知时（limit<=0）一律不裁。
func (g *Gateway) capMaxTokens(chat jsonObj) {
	model := str(chat["model"])
	if model == "" {
		return
	}
	_, limit := g.modelPlan(model)
	if limit <= 0 {
		return
	}
	for _, k := range []string{"max_tokens", "max_completion_tokens", "max_output_tokens"} {
		v, ok := chat[k].(float64)
		if !ok || v <= 0 || v <= float64(limit) {
			continue
		}
		chat[k] = limit
		Trace("inference", "clamp", "输出上限超模型能力，已裁剪", 0, jsonObj{
			"model": model, "field": k, "from": int64(v), "to": limit,
		})
	}
}


func (g *Gateway) serveOpenAI(w http.ResponseWriter, appName, protocol string, chat jsonObj, rawReq []byte) {
	stream := true
	if v, ok := chat["stream"].(bool); ok {
		stream = v
	}
	g.capMaxTokens(chat)
	upstreamBody := buildUpstreamBody(chat, g.desensitize)

	// 请求级上下文：后续所有事件都带同一个 reqId，供因果倒推
	reqId := Trace("inference", "start", "收到推理请求", 0, jsonObj{
		"protocol": protocol, "model": str(chat["model"]),
		"app": appName, "stream": stream, "desensitize": g.desensitize,
		"concurrency": g.limiter.stats(),
	})

	tried := map[string]bool{}
	var resp *http.Response
	var acc *Account
	var err error
	for i := 0; i < 5; i++ {
		resp, acc, err = g.openUpstream(upstreamBody, tried)
		if err == nil {
			break
		}
		// 请求自身的问题（超上下文/内容拦截）：换任何号结果都一样，
		// 继续重试只会白烧健康账号的请求配额
		if isClientErr(err) {
			break
		}
		if i < 4 {
			Trace("inference", "retry", "换号重试", reqId, jsonObj{"attempt": i + 1, "err": err.Error()})
		}
	}
	if err != nil {
		TraceFail("inference", "上游不可用，请求失败", reqId, jsonObj{
			"tried_accounts": len(tried), "err": err.Error(),
		})
		status, message := upstreamErrPayload(err)
		writeJSON(w, status, jsonObj{"error": jsonObj{"message": message, "type": "upstream_error"}})
		return
	}
	g.pool.MarkSuccess(acc.UID)
	defer g.pool.Release(acc.UID)

	started := time.Now()
	model := str(chat["model"])
	defer func() {
		Trace("inference", "ok", "请求完成", reqId, jsonObj{
			"uid": acc.UID, "model": model, "tried": len(tried),
			"duration_ms": time.Since(started).Milliseconds(),
		})
	}()

	if !stream {
		// 非流式：把上游全部块聚合成一个完整响应
		var content, reasoning strings.Builder
		calls := map[int]*toolCallAcc{}
		var usage *chatChunk
		finish := "stop"
		proxyStream(w, resp, false, func(c *chatChunk) []byte {
			if c == nil {
				return nil
			}
			if c.Usage != nil {
				usage = c
			}
			for _, ch := range c.Choices {
				content.WriteString(ch.Delta.Content)
				reasoning.WriteString(ch.Delta.ReasoningContent)
				for _, tc := range ch.Delta.ToolCalls {
					a := calls[tc.Index]
					if a == nil {
						a = &toolCallAcc{}
						calls[tc.Index] = a
					}
					if tc.ID != "" {
						a.ID = tc.ID
					}
					a.Name += tc.Function.Name
					a.Args += tc.Function.Arguments
				}
				if ch.FinishReason != nil {
					finish = *ch.FinishReason
				}
			}
			return nil
		})
		msg := jsonObj{"role": "assistant", "content": content.String()}
		if reasoning.Len() > 0 {
			msg["reasoning_content"] = reasoning.String()
		}
		if len(calls) > 0 {
			msg["tool_calls"] = flattenToolCalls(calls)
		}
		choice := jsonObj{"index": 0, "message": msg, "finish_reason": finish}
		out := jsonObj{
			"id":      randomID("chatcmpl-"),
			"object":  "chat.completion",
			"created": time.Now().Unix(),
			"model":   model,
			"choices": []any{choice},
		}
		if usage != nil && usage.Usage != nil {
			out["usage"] = usage.Usage
		}
		writeJSON(w, 200, out)
		g.recordUsage(appName, acc.UID, protocol, model, started, usage, rawReq, content.String(), "ok", "")
		return
	}

	w.Header().Set("Content-Type", "text/event-stream")
	w.Header().Set("Cache-Control", "no-cache")
	w.Header().Set("Connection", "keep-alive")
	w.WriteHeader(200)
	flusher, _ := w.(http.Flusher)

	var content, reasoning strings.Builder
	var usage *chatChunk
	err = proxyStream(w, resp, true, func(c *chatChunk) []byte {
		if c == nil {
			return []byte("data: [DONE]\n\n")
		}
		if c.Usage != nil {
			usage = c
		}
		for _, ch := range c.Choices {
			content.WriteString(ch.Delta.Content)
			reasoning.WriteString(ch.Delta.ReasoningContent)
		}
		// 记账用原始数据（包含思维链），透传给客户端的则先清洗。
		// 上游每个 chunk 都带空 content/reasoning_content，
		// 原样透传会让客户端渲染出空白思考块。
		out := sanitizeChunkForClient(c)
		if out == nil {
			return nil
		}
		return sseData(out)
	})
	if flusher != nil {
		flusher.Flush()
	}
	status := "ok"
	errText := ""
	if err != nil {
		status = "error"
		errText = err.Error()
	}
	g.recordUsage(appName, acc.UID, protocol, model, started, usage, rawReq, content.String(), status, errText)
}

// serveAnthropic 处理 Anthropic Messages 入站协议的流式转发。
// 上游返回 OpenAI 格式，这里逐块翻译成 Anthropic 的事件序列。
func (g *Gateway) serveAnthropic(w http.ResponseWriter, appName string, chat jsonObj, rawReq []byte) {
	g.capMaxTokens(chat)
	upstreamBody := buildUpstreamBody(chat, g.desensitize)
	tried := map[string]bool{}
	var resp *http.Response
	var acc *Account
	var err error
	for i := 0; i < 5; i++ {
		resp, acc, err = g.openUpstream(upstreamBody, tried)
		if err == nil {
			break
		}
		if isClientErr(err) {
			break
		}
	}
	if err != nil {
		status, message := upstreamErrPayload(err)
		writeJSON(w, status, jsonObj{"type": "error", "error": jsonObj{"type": "api_error", "message": message}})
		return
	}
	g.pool.MarkSuccess(acc.UID)
	defer g.pool.Release(acc.UID)

	started := time.Now()
	model := str(chat["model"])
	msgID := randomID("msg_")
	stream := true
	if v, ok := chat["stream"].(bool); ok {
		stream = v
	}

	var content strings.Builder
	var usage *chatChunk
	finish := "end_turn"

	translate := func(c *chatChunk, firstSent *bool) []byte {
		var sb strings.Builder
		if !*firstSent {
			*firstSent = true
			sb.WriteString(sseEvent("message_start", jsonObj{
				"type": "message_start",
				"message": jsonObj{
					"id": msgID, "type": "message", "role": "assistant",
					"model": model, "content": []any{},
					"usage": jsonObj{"input_tokens": 0, "output_tokens": 0},
				},
			}))
			sb.WriteString(sseEvent("content_block_start", jsonObj{
				"type": "content_block_start", "index": 0,
				"content_block": jsonObj{"type": "text", "text": ""},
			}))
		}
		for _, ch := range c.Choices {
			if ch.Delta.Content != "" {
				content.WriteString(ch.Delta.Content)
				sb.WriteString(sseEvent("content_block_delta", jsonObj{
					"type": "content_block_delta", "index": 0,
					"delta": jsonObj{"type": "text_delta", "text": ch.Delta.Content},
				}))
			}
			if ch.FinishReason != nil {
				switch *ch.FinishReason {
				case "length":
					finish = "max_tokens"
				case "tool_calls":
					finish = "tool_use"
				default:
					finish = "end_turn"
				}
			}
		}
		return []byte(sb.String())
	}

	if !stream {
		var firstSent bool
		proxyStream(w, resp, false, func(c *chatChunk) []byte {
			if c == nil {
				return nil
			}
			if c.Usage != nil {
				usage = c
			}
			translate(c, &firstSent)
			return nil
		})
		out := jsonObj{
			"id": msgID, "type": "message", "role": "assistant",
			"model": model,
			"content": []any{jsonObj{"type": "text", "text": content.String()}},
			"stop_reason": finish,
			"usage": jsonObj{"input_tokens": usageTokens(usage, true), "output_tokens": usageTokens(usage, false)},
		}
		writeJSON(w, 200, out)
		g.recordUsage(appName, acc.UID, "anthropic-messages", model, started, usage, rawReq, content.String(), "ok", "")
		return
	}

	w.Header().Set("Content-Type", "text/event-stream")
	w.Header().Set("Cache-Control", "no-cache")
	w.WriteHeader(200)
	var firstSent bool
	proxyStream(w, resp, true, func(c *chatChunk) []byte {
		if c == nil {
			return []byte(sseEvent("message_stop", jsonObj{"type": "message_stop"}))
		}
		if c.Usage != nil {
			usage = c
		}
		return translate(c, &firstSent)
	})
	writeAll(w, sseEvent("content_block_stop", jsonObj{"type": "content_block_stop", "index": 0}))
	writeAll(w, sseEvent("message_delta", jsonObj{
		"type": "message_delta",
		"delta": jsonObj{"stop_reason": finish, "stop_sequence": nil},
		"usage": jsonObj{"output_tokens": usageTokens(usage, false)},
	}))
	writeAll(w, sseEvent("message_stop", jsonObj{"type": "message_stop"}))
	if f, ok := w.(http.Flusher); ok {
		f.Flush()
	}
	g.recordUsage(appName, acc.UID, "anthropic-messages", model, started, usage, rawReq, content.String(), "ok", "")
}

type toolCallAcc struct {
	ID   string
	Name string
	Args string
}

func flattenToolCalls(calls map[int]*toolCallAcc) []any {
	out := make([]any, 0, len(calls))
	for i := 0; i < len(calls); i++ {
		a := calls[i]
		if a == nil {
			continue
		}
		out = append(out, jsonObj{
			"id": a.ID, "type": "function",
			"function": jsonObj{"name": a.Name, "arguments": a.Args},
		})
	}
	return out
}

func sseEvent(event string, payload any) string {
	b, _ := json.Marshal(payload)
	return fmt.Sprintf("event: %s\ndata: %s\n\n", event, string(b))
}

func writeAll(w io.Writer, s string) {
	w.Write([]byte(s))
}

func usageTokens(u *chatChunk, prompt bool) int {
	if u == nil || u.Usage == nil {
		return 0
	}
	if prompt {
		return u.Usage.PromptTokens
	}
	return u.Usage.CompletionTokens
}
// usageBodyLimit 是 usage_logs 里请求/响应正文的落盘上限（字节）。
//
// 这两列只写不读（Go 与 Kotlin 均无读取方），定位是取证材料而非全量账本，
// 所以只留开头一段就够定位问题；全文落盘会让库随对话无限膨胀
// （实测 36 条就堆了 294KB，其中单条最长 18KB）。
const usageBodyLimit = 4096

func (g *Gateway) recordUsage(appName, uid, protocol, model string, started time.Time, u *chatChunk, reqBody []byte, respBody, status, errText string) {
	log := &UsageLog{
		CreatedAt:    time.Now().Unix(),
		AppName:      appName,
		AccountUID:   uid,
		Protocol:     protocol,
		Model:        model,
		Status:       status,
		Error:        errText,
		DurationMs:   time.Since(started).Milliseconds(),
		RequestBody:  truncateUTF8Bytes(reqBody, usageBodyLimit),
		ResponseBody: truncateUTF8(respBody, usageBodyLimit),
	}
	if u != nil && u.Usage != nil {
		log.PromptTokens = u.Usage.PromptTokens
		log.CompletionTokens = u.Usage.CompletionTokens
		log.TotalTokens = u.Usage.TotalTokens
		// 积分与缓存命中此前每请求都算了却没落库，统计页只能显示 0。
		log.Credits = u.Usage.Credit()
		log.CachedTokens = u.Usage.CachedTokens
	}
	g.store.LogUsage(log)

	// 只有成功的请求才喂成本台账：失败请求的 usage 要么缺失、要么不含真实扣费，
	// 拿它去平滑单价会把价格带偏（一个失败样本能把 EMA 拽走 30%）。
	if status == "ok" && uid != "" && model != "" {
		credits := 0.0
		tokens := log.TotalTokens
		if u != nil && u.Usage != nil {
			credits = u.Usage.Credit()
			if tokens == 0 {
				tokens = u.Usage.PromptTokens + u.Usage.CompletionTokens
			}
		}
		if tokens > 0 {
			g.pool.RecordCost(uid, model, credits, tokens)
			// 一并落库，重启后不必重新学价格（观测过期由 PruneCosts 清）
			if cost, ok := g.pool.costSnapshot(uid, model); ok {
				_ = g.store.SaveCost(costRow{
					UID: uid, Model: model, CostPer1K: cost.cost,
					Samples: cost.samples, UpdatedAt: cost.ts,
				})
			}
		}
	}
}

// ---------------- 模型与计数 ----------------

func (g *Gateway) handleModels(w http.ResponseWriter, r *http.Request) {
	models, _, err := g.listModels()
	if err != nil {
		writeJSON(w, 200, jsonObj{"object": "list", "data": []any{}})
		return
	}
	writeJSON(w, 200, jsonObj{"object": "list", "data": models})
}

func (g *Gateway) handleCountTokens(w http.ResponseWriter, r *http.Request) {
	raw, _ := readBody(r)
	var body jsonObj
	json.Unmarshal(raw, &body)
	// 上游无精确计数接口，用字符数近似，足够客户端做上下文预算。
	// 直接量 len(raw)：写 len(string(raw)) 会先把整份字节复制成 string，
	// 而这里算的根本不是字符数、只是长度除以 4——大请求体下白复制几十 MB。
	approx := len(raw) / 4
	writeJSON(w, 200, jsonObj{"input_tokens": approx})
}
