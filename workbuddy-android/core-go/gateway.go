package main

import (
	"bufio"
	"bytes"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"strings"
	"sync"
	"time"
)

// Gateway 是本地的 OpenAI/Anthropic 兼容服务。它接收客户端请求，
// 选号后转成上游格式发出，再把上游 SSE 流按客户端协议转回去。
type Gateway struct {
	port    int
	store   *Store
	pool    *AccountPool
	http    *httpClient
	server  *http.Server
	login   *loginManager
	started time.Time
	addr    string
	limiter *concurrencyLimiter
	rate    *accountRateLimiter
	// desensitize 是反审核脱敏开关：开时只改 system 消息里的合规声明用词。
	desensitize bool
	// sessions 是会话粘性路由：同一会话尽量复用同一账号，保上游 prompt cache 命中。
	sessions *SessionRouter
	// inspector 记录实际发出的头与体，供与官方客户端逐项比对（默认关闭）。
	inspector *RequestInspector
	// sched 驱动每日/定时的后台任务（签到、续期、目录刷新、清理）。
	sched *Scheduler
	// modelLimits 是区域 →（模型 → 最大输出 token）的查询表。
	// 目录在 DB 里是整段 JSON，每次请求都反序列化太重，故在内存缓一份；
	// 同时记下对应的原文，原文一变就重建——否则会漏掉没挂钩子的刷新路径。
	limitsMu    sync.Mutex
	modelLimits map[string]map[string]int64
	limitsRaw   map[string]string
	// limitsIDs 是区域 → 目录里出现过的模型 id 集合。
	// 与 modelLimits 分开存：后者只收有 maxOutputTokens 的模型，
	// 拿它判区域会漏掉没标上限的那些，白白不限定区域。
	limitsIDs map[string]map[string]bool
}

func NewGateway(port int, store *Store, pool *AccountPool, h *httpClient) *Gateway {
	g := &Gateway{
		port:    port,
		store:   store,
		pool:    pool,
		http:    h,
		login:   newLoginManager(h),
		started: time.Now(),
		rate:     newAccountRateLimiter(rateGap(store), rateJitter(store)),
		sessions:  NewSessionRouter(30*time.Minute, 2000),
		inspector: NewRequestInspector(),
		desensitize: desensitizeEnabled(store),
	}
	g.sched = newScheduler(g)
	// 取证开关从设置恢复：否则重启后自动关掉，用户会以为「开了但没记录」。
	if inspectorEnabled(store) {
		g.inspector.SetEnabled(true)
	}
	return g
}

func (g *Gateway) Start() error {
	mux := http.NewServeMux()
	mux.HandleFunc("/v1/chat/completions", g.handleChat)
	mux.HandleFunc("/v1/responses", g.handleResponses)
	mux.HandleFunc("/v1/messages", g.handleMessages)
	mux.HandleFunc("/v1/models", g.handleModels)
	mux.HandleFunc("/v1/count_tokens", g.handleCountTokens)
	mux.HandleFunc("/", g.handleRoot)

	// 先同步监听：端口被占用等错误必须当场返回给界面，
	// 否则用户只会看到「点了没反应」。
	ln, err := net.Listen("tcp", fmt.Sprintf("0.0.0.0:%d", g.port))
	if err != nil {
		TraceFail("gateway", "端口监听失败", 0, jsonObj{"port": g.port, "err": err.Error()})
		return fmt.Errorf("端口 %d 无法监听（可能已被占用）: %w", g.port, err)
	}

	g.server = &http.Server{
		Handler:           g.withIPControl(mux),
		ReadHeaderTimeout: 20 * time.Second,
	}
	g.addr = ln.Addr().String()
	Trace("gateway", "ok", "网关已开始监听", 0, jsonObj{
		"addr": g.addr, "port": g.port,
		"ip_mode": g.store.ipAccessMode(), "desensitize": g.desensitize,
	})
	go func() {
		_ = g.server.Serve(ln)
	}()
	// 调度器与网关同生共死：网关停了，没有请求要服务，继续跑定时任务只会
	// 对着一个已经关掉的监听地址空转。
	g.sched.Start()
	return nil
}

// ReloadPort 从设置里重读监听端口。改完端口后调它，再重启网关即生效，
// 不必重启应用；构造函数传入的端口只作为初值。
func (g *Gateway) ReloadPort() {
	g.port = listenPort(g.store)
}

// rateGap 读取账号级限速的最小间隔（毫秒），默认 1500ms（1.5s）。
// 参考上游实践：这是最容易被风控识别为机器人的地方，宁可慢一点。
func rateGap(store *Store) time.Duration {
	const defaultMs = 1500
	n := 0
	if _, err := fmt.Sscanf(store.GetSetting("rate_min_interval_ms", ""), "%d", &n); err != nil || n < 0 || n > 60000 {
		n = defaultMs
	}
	return time.Duration(n) * time.Millisecond
}

// rateJitter 读取限速抖动幅度（毫秒），默认 300ms。
// 抖动避免固定节拍本身成为指纹。
func rateJitter(store *Store) time.Duration {
	const defaultMs = 300
	n := 0
	if _, err := fmt.Sscanf(store.GetSetting("rate_jitter_ms", ""), "%d", &n); err != nil || n < 0 || n > 10000 {
		n = defaultMs
	}
	return time.Duration(n) * time.Millisecond
}

// desensitizeEnabled 读取反审核脱敏开关。默认开启：被拦的是合规声明，
// 脱敏不影响语义，不开关等于白白被拦。
func desensitizeEnabled(store *Store) bool {
	return store.GetSetting("desensitize", "1") != "0"
}

// ReloadRateLimit 配置改动后重建限速器。
func (g *Gateway) ReloadRateLimit() {
	g.rate = newAccountRateLimiter(rateGap(g.store), rateJitter(g.store))
}

// ReloadProcessors 配置改动后重读限速与脱敏开关。
func (g *Gateway) ReloadProcessors() {
	g.ReloadRateLimit()
	g.desensitize = desensitizeEnabled(g.store)
	// 开关也要重读：默认关闭的取证功能，用户打开后不能还要重启一次才生效。
	g.inspector.SetEnabled(inspectorEnabled(g.store))
	Trace("gateway", "reload", "处理链已重载", 0, jsonObj{
		"desensitize": g.desensitize,
		"inspector":   g.inspector.Enabled(),
		"rate_ms":     int(rateGap(g.store).Milliseconds()),
		"jitter_ms":   int(rateJitter(g.store).Milliseconds()),
	})
}

// withIPControl 在 mux 外层做 IP 白/黑名单管控。
//
// 放在最外层而不是每个 handler 里去判：漏一个 handler 就是一个绕过口，
// 而这类管控最怕的就是「有遗漏」。
func (g *Gateway) withIPControl(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		peer, _, _ := net.SplitHostPort(r.RemoteAddr)
		ip := g.store.clientIP(peer, map[string]string{
			"x-real-ip":       r.Header.Get("X-Real-IP"),
			"x-forwarded-for": r.Header.Get("X-Forwarded-For"),
		})
		if !g.store.allowIP(ip) {
			g.store.LogIPAccess(ip, r.URL.Path, true, r.UserAgent())
			TraceFail("ipcontrol", "来源 IP 被拦", 0, jsonObj{
				"ip": ip, "peer": peer, "path": r.URL.Path, "mode": g.store.ipAccessMode(),
			})
			writeJSON(w, 403, jsonObj{"error": jsonObj{
				"message": "来源 IP 不在允许范围内",
				"type":    "permission_error",
			}})
			return
		}
		next.ServeHTTP(w, r)
	})
}

func (g *Gateway) Stop() error {
	if g.sched != nil {
		g.sched.Stop()
	}
	if g.server == nil {
		return nil
	}
	g.addr = ""
	return g.server.Close()
}

// Address 返回当前实际监听地址，供界面展示给用户。
// 监听通配地址时展示 0.0.0.0，比 [::] 更易理解。
func (g *Gateway) Address() string {
	if g.addr == "" {
		return ""
	}
	host, port, err := net.SplitHostPort(g.addr)
	if err != nil {
		return "http://" + g.addr
	}
	if host == "::" || host == "0.0.0.0" || host == "" {
		host = "0.0.0.0"
	}
	return "http://" + net.JoinHostPort(host, port)
}

func (g *Gateway) handleRoot(w http.ResponseWriter, r *http.Request) {
	if r.URL.Path != "/" {
		writeJSON(w, 404, jsonObj{"error": jsonObj{"message": "not found", "type": "invalid_request_error"}})
		return
	}
	writeJSON(w, 200, jsonObj{
		"status":  "ok",
		"service": "workbuddy-manager",
		"uptime":  int(time.Since(g.started).Seconds()),
	})
}

// keyHash 派生 API Key 的存储哈希：HMAC-SHA256，密钥为固定用途前缀。
//
// 必须与 Rust 侧 core-rs/src/lib.rs 的 key_hash 逐字节一致：密钥明文由 Rust 生成、
// 哈希后入库，网关这里若算不出同一个值就直接查不到表，**所有密钥都会失效**。
// 用 HMAC 而非裸 SHA256：避免同一明文在不同用途下产生相同摘要，也避免彩虹表命中。
func keyHash(key string) string {
	mac := hmac.New(sha256.New, []byte("workbuddy-apikey-v1"))
	mac.Write([]byte(key))
	return hex.EncodeToString(mac.Sum(nil))
}

// authorize 校验客户端 API Key。未配置任何 Key 时放行（首次使用免鉴权）。
func (g *Gateway) authorize(r *http.Request) (string, error) {
	keys, err := g.store.ListApps()
	if err != nil {
		TraceFail("auth", "读取密钥列表失败", 0, jsonObj{"err": err.Error()})
		return "", err
	}
	if len(keys) == 0 {
		return "anonymous", nil
	}
	token := extractAPIKey(r)
	if token == "" {
		TraceFail("auth", "请求缺少 API Key", 0, jsonObj{
			"path": r.URL.Path, "ua": truncateTrace(r.UserAgent(), 80),
		})
		return "", fmt.Errorf("缺少 API Key")
	}
	app, err := g.store.LookupApp(keyHash(token))
	if err != nil {
		// 不回记录明文 key，只记前绥位供对账
		TraceFail("auth", "API Key 无效", 0, jsonObj{
			"prefix": truncateTrace(token, 10), "path": r.URL.Path,
		})
		return "", fmt.Errorf("API Key 无效")
	}
	return app.Name, nil
}

func extractAPIKey(r *http.Request) string {
	if auth := r.Header.Get("Authorization"); strings.HasPrefix(auth, "Bearer ") {
		return strings.TrimSpace(strings.TrimPrefix(auth, "Bearer "))
	}
	if v := r.Header.Get("x-api-key"); v != "" {
		return strings.TrimSpace(v)
	}
	return ""
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(v)
}

// maxBodyBytes 是请求体上限。压缩上下文这类请求会带上整段历史，体积很大，
// 但仍必须有上限——不然一个畸形请求就能把内存打满（手机上进程会被 LMK 直接回收）。
const maxBodyBytes = 32 << 20

// errBodyTooLarge 表示请求体超过上限。
var errBodyTooLarge = errors.New("请求体超过上限")

// readBody 读取请求体；超限时**明确报错，而不是静默截断**。
//
// 之前用裸 io.LimitReader：超限时返回的是「被砍掉的前 32MB」，它必然不是合法 JSON，
// 于是客户端收到的是「请求体不是合法 JSON」——真正的原因（请求太大）完全丢失，
// 用户只会看到一句莫名其妙的报错；而且这条路径在 inference 打点之前就返回了，
// 日志里连一行都没有，事后只能靠猜。
// 现在两种失败分开表达，调用方据此给出可定位的原因。
func readBody(r *http.Request) ([]byte, error) {
	defer r.Body.Close()
	// 多读 1 字节：能读到上限之外，才说明确实超了，而不是刚好卡在边界。
	data, err := io.ReadAll(io.LimitReader(r.Body, maxBodyBytes+1))
	if err != nil {
		return nil, err
	}
	if len(data) > maxBodyBytes {
		return nil, errBodyTooLarge
	}
	return data, nil
}

// bodyErrorPayload 把读体的失败翻译成「状态码 + 用户能看懂的原因」。
//
// 未预料到的读失败（连接中断等）单独一条分支：它和「体积超限」是两回事，
// 混为一谈会把网络问题说成请求格式问题，误导排查方向。
func bodyErrorPayload(err error) (int, string) {
	if errors.Is(err, errBodyTooLarge) {
		// 文案要说准成因，否则会把用户往错方向引。
		// 算过账：1M token 的纯文本只有 3~4 MB（中文约 1 token/字，每字 UTF-8 3 字节；
		// 英文约 4 字符/token），而目录里 maxInputTokens 最大就是 1000000。
		// 所以**纯文本的上下文压缩根本撞不到 32MB 这个上限**，
		// 真正能撞到的是 base64 图片（1MB 的图编码后约 1.33MB，二十来张就到顶）。
		return 413, fmt.Sprintf("请求体超过上限（%d MB）：多半是消息里的图片过多（base64 编码后体积膨胀约 1/3）。请减少图片数量或尺寸后重试", maxBodyBytes>>20)
	}
	return 400, "读取请求体失败: " + err.Error()
}

// logRejectedBody 记录被拒的请求体。
//
// 必须打点：这几条早退路径都发生在 inference:start 之前，
// 不打点就等于「什么都没发生过」——排查时日志里一片空白，连有没有请求到过网关都看不出来。
func logRejectedBody(r *http.Request, protocol string, status int, reason string) {
	TraceFail("inference", "请求被拒", 0, jsonObj{
		"protocol": protocol, "path": r.URL.Path, "status": status,
		"reason": reason, "content_length": r.ContentLength,
	})
}

// openUpstream 选中账号并建立上游流式连接。
//
// 选号顺序：**先试会话粘住的账号**（保上游 prompt cache 命中），
// 粘不住或不行再走常规加权轮换。
//
// 返回的 Account 已被占用一个并发名额，调用方用完必须调 pool.Release(acc.UID)。
// tried 里记录已试过的 uid，把已经失败的号排除掉继续挑，这样多账号才能真的
// 起到容错作用——否则选到重复号就直接放弃。
// upstreamError 携带上游的真实状态码与原始响应体，交给调用方决定回给客户端什么。
//
// 为何不直接拼错误字符串：错误分类器（classify）本就吃状态码与原始响应体，
// 把两者一路带出去，调用方就能用同一口径生成响应；此前只传 action.Reason，
// 导致 FailFast 类错误（其 Reason 为空）回给客户端的 message 是空串。
type upstreamError struct {
	status int
	kind   ErrKind
	raw    []byte
}

func (e *upstreamError) Error() string {
	return string(e.kind)
}

// isTransientConnErr：对端 reset / idle 连接被收走等网络层瞬时错误。
// 这类错发生在请求刚出洞时，服务端多半根本没看到，重打是安全的；
// 已收到响应头之后（如流中断）不算——那个应该由流层自己处置。
func isTransientConnErr(err error) bool {
	if err == nil {
		return false
	}
	if errors.Is(err, net.ErrClosed) || errors.Is(err, io.ErrUnexpectedEOF) {
		return true
	}
	s := err.Error()
	for _, p := range []string{
		"connection reset by peer",
		"server closed idle connection",
		"use of closed network connection",
		"broken pipe",
	} {
		if strings.Contains(s, p) {
			return true
		}
	}
	return false
}

// errNoAccount：池里没有任何可用账号（空池/全被占用/区域不匹配）。
// 换号重试对此无意义，isClientErr 据此直接短路。
var errNoAccount = errors.New("没有可用账号")

// upstreamErrPayload 把上游失败转成回给客户端的状态码与已脱敏文案。
//
// 用上游**自己的**状态码（如 400），客户端才能分清「请求本身有问题」与「上游挂了」；
// 之前一律回 502，超上下文被当成服务端故障，客户端的重试策略会对着同一份超长请求反复重打。
func upstreamErrPayload(err error) (int, string) {
	var ue *upstreamError
	if errors.As(err, &ue) {
		return ue.status, errMessage(safeErr(ue.raw, ue.status))
	}
	return 502, err.Error()
}

// isClientErr 表示这是「请求自身的问题」，换号重试没有意义。
//
// 无号可用也属此类：换号重试是在「有号但这个不行」的前提下才有意义，
// 池子空了/全被占用时重试每轮都是同一个错，只是空转并堆日志
//（真机日志观测到单请求连打 4 轮「换号重试」的空转）。
func isClientErr(err error) bool {
	if errors.Is(err, errNoAccount) {
		return true
	}
	var ue *upstreamError
	return errors.As(err, &ue)
}

// errMessage 从脱敏后的错误体里取一句可展示的文案。
func errMessage(payload jsonObj) string {
	if s := str(payload["message"]); s != "" {
		return s
	}
	if s := str(payload["msg"]); s != "" {
		return s
	}
	if inner, ok := payload["error"].(map[string]any); ok {
		if s := str(inner["message"]); s != "" {
			return s
		}
	}
	return "上游拒绝了本次请求"
}

// openUpstream 选中账号并建立上游流式连接。
//
// 选号顺序：**先试会话粘住的账号**（保上游 prompt cache 命中），
// 粘不住或不行再走常规加权轮换。
//
// 返回的 Account 已被占用一个并发名额，调用方用完必须调 pool.Release(acc.UID)。
// tried 里记录已试过的 uid，把已经失败的号排除掉继续挑，这样多账号才能真的
// 起到容错作用——否则选到重复号就直接放弃。
//
// 区域不是参数，而是由模型目录推得：混池（国内版 + 国际版账号同时在池）下，
// 把国内专属模型发给国际版账号会吃 11102（该后端无此模型），
// 所以候选要收敛到「目录里确实有这个模型」的区域。
// 目录里没这个模型（别名/新模型/还没拉到）则不限定区域，
// 否则未知模型会被直接打成不可用。
func (g *Gateway) openUpstream(body []byte, tried map[string]bool) (*http.Response, *Account, error) {
	model := extractModelFromBody(body)
	realms, _ := g.modelPlan(model)
	var acc *Account

	// 1) 会话粘性：同一会话尽量复用同一账号，避免打散上游前缀缓存。
	//    仅用于**首次尝试**（len(tried)==0）——重试时应当换号。
	if g.sessions != nil && len(tried) == 0 {
		if key := extractSessionKey(parseBodyJSON(body)); key != "" {
			if uid := g.sessions.lookup(key, g.pool); uid != "" && !tried[uid] {
				acc = g.pool.acquireByUIDInRealms(uid, model, realms)
			}
		}
	}
	// 2) 常规加权轮换
	if acc == nil {
		acc = g.pool.AcquireInRealms(realms, model, tried)
	}
	if acc == nil {
		if len(tried) > 0 {
			TraceFail("upstream", "所有账号均已尝试失败", 0, jsonObj{"tried": len(tried)})
			return nil, nil, fmt.Errorf("已尝试 %d 个账号均失败: %w", len(tried), errNoAccount)
		}
		TraceFail("upstream", "没有可用账号", 0, jsonObj{"model": model})
		return nil, nil, errNoAccount
	}
	tried[acc.UID] = true
	// 选号后绑定会话：后续同会话的请求优先命中这个账号
	if g.sessions != nil {
		if key := extractSessionKey(parseBodyJSON(body)); key != "" {
			g.sessions.bind(key, acc.UID)
		}
	}

	// 账号级限速：同一账号两次上游请求之间补足最小间隔。
	// 放在选号之后、发请求之前——限速的对象是账号，不是全局。
	if g.rate != nil {
		if waited := g.rate.wait(acc.UID); waited > 0 {
			Trace("ratelimit", "wait", "触发限速等待", 0, jsonObj{
				"uid": acc.UID, "waited_ms": waited.Milliseconds(),
			})
		}
	}
	sid := Trace("upstream", "start", "向上游发起请求", 0, jsonObj{
		"uid": acc.UID, "realm": realmOf(acc.Domain), "attempt": len(tried),
	})

	r := realmOf(acc.Domain)
	ep := regionTable[r]
	req, err := http.NewRequest("POST", ep.chatBase+"/v2/chat/completions", bytes.NewReader(body))
	if err != nil {
		g.pool.Release(acc.UID)
		return nil, acc, err
	}
	// GetBody 让传输层能在「请求还没写出去就断」的场景自行重放请求体；
	// 下面的一次显式重试则覆盖「写出去之后才断」的场景。
	req.GetBody = func() (io.ReadCloser, error) {
		return io.NopCloser(bytes.NewReader(body)), nil
	}
	req.Header.Set("Content-Type", "application/json")
	for k, v := range chatHeaders(r, acc) {
		req.Header.Set(k, v)
	}
	// 出网取证：记录「临门一脚」的实际头与体（默认关闭，开启时才有开销）
	if g.inspector != nil {
		hdrs := make(map[string]string, len(req.Header))
		for k, v := range req.Header {
			if len(v) > 0 {
				hdrs[k] = v[0]
			}
		}
		g.inspector.Record(req.Method, req.URL.String(), acc.UID, model, hdrs, body)
	}

	client := newStreamClient()
	resp, err := client.Do(req)
	if err != nil && isTransientConnErr(err) {
		// 瞬时断连（idle 连接被上游收走/对端 reset）：拿到的可能是一个根本
		// 没被服务端看到的请求，同号立即重打一次（真机日志中这类错此前
		// 直接把整个请求打挂）。只重一次：连错两次说明不是连接陈旧问题。
		Trace("upstream", "retry", "瞬时断连，同号重试", sid, jsonObj{"uid": acc.UID, "err": err.Error()})
		req2, e := http.NewRequest("POST", ep.chatBase+"/v2/chat/completions", bytes.NewReader(body))
		if e == nil {
			req2.Header.Set("Content-Type", "application/json")
			for k, v := range chatHeaders(r, acc) {
				req2.Header.Set(k, v)
			}
			resp, err = client.Do(req2)
		}
	}
	if err != nil {
		// 传输层失败（连接被重置等）：无状态码可分类，按无权威分类处理——
		// 短冷却并换号（参考实现里这类失敗要喂连败计数，见 pool 的 degrades）
		g.pool.MarkFailure(acc.UID, 0)
		g.pool.Release(acc.UID)
		TraceFail("upstream", "请求上游失败", sid, jsonObj{"uid": acc.UID, "err": err.Error()})
		return nil, acc, err
	}

	if resp.StatusCode != 200 {
		raw, _ := io.ReadAll(io.LimitReader(resp.Body, 4096))
		resp.Body.Close()
		g.pool.Release(acc.UID)

		// 分类驱动处置：把「哪个错、罚不罚号、换不换号、要不要摘号」拆开判。
		// 之前只分「429/5xx 可重试」与「其他扣一分钟」两档，代价很实在：
		//   · 超上下文/内容拦截这类请求自身问题会去冷却无辜账号；
		//   · 11102「该后端无此模型」会落 4xx 兜底（只换号不避让），坏号留在池内反复被选中。
		kind := classify(resp.StatusCode, raw)
		action := actionFor(kind, raw, respHeaders(resp), time.Now())
		g.applyUpstreamAction(acc.UID, model, action)

		TraceFail("upstream", "上游返回错误", sid, jsonObj{
			"uid": acc.UID, "status": resp.StatusCode, "kind": string(kind),
			"rotate": action.Rotate, "cooldown_s": action.Cooldown,
			"reason": action.Reason, "body": truncateTrace(string(raw), 200),
		})

		// 请求自身的问题：同一 body 换任何账号结果都一样，不轮转，直接判失败
		if action.FailFast {
			return nil, nil, &upstreamError{status: resp.StatusCode, kind: kind, raw: raw}
		}
		return nil, acc, fmt.Errorf("%s", action.Reason)
	}
	return resp, acc, nil
}

// applyUpstreamAction 把错误分类给出的处置落到账号池上。
//
// 单独拆出来是为了能被单测直接打到：分类正确不代表落地正确，
// 上一版就是分类给出 FailFast（不罚号）而落地分支却跑了 MarkFailure（递增式罚号）。
// 只测分类那个纯函数，这类「分类对、落地错」的缺陷测不出来。
//
// 五档顺序即优先级，**不能合并**：
//   - ModelScoped 是 (账号,模型) 级避让——它同时带 Cooldown>0，
//     那就是该模型的 TTL，不是账号冷却；若被下一档先吃，会顺手罚整个号。
//   - FailFast 是请求自身的问题（超上下文/内容拦截）：换任何号结果都一样，
//     actionFor 给它的 Cooldown 是 0，本就不该罚号。
//   - Rotate 为假且 Cooldown 为 0：分类说不出所以然的失败，单次不罚，
//     连成串才临时出池（NoteFailures）；否则正常重试会把每一次抖动都堆成冷却。
func (g *Gateway) applyUpstreamAction(uid, model string, action Action) {
	switch {
	case action.Disable:
		g.pool.MarkDisabled(uid, action.Reason)
	case action.ModelScoped:
		if model != "" {
			g.pool.MarkModelBlocked(uid, model, time.Duration(action.Cooldown*float64(time.Second)))
		}
	case action.FailFast:
		// 不罚号、不轮转
	case action.Cooldown > 0:
		g.pool.MarkFailure(uid, time.Duration(action.Cooldown*float64(time.Second)))
	case !action.Rotate:
		g.pool.NoteFailures(uid, failStreakThreshold, failDegradeSeconds)
	}
}

// proxyStream 把上游 SSE 逐行转发给客户端，由 transform 决定每块的输出内容。
// transform 返回 nil 表示该块不输出（例如心跳或空 delta）。
//
// clientStreaming 表示客户端侧是否为 SSE 流式响应：只有流式路径才允许心跳看门狗
// 往 w 写 SSE 注释。非流式路径（openai-chat / anthropic-messages 的聚合分支）在
// 聚合完成后才 writeJSON，若看门狗在中途写入心跳，客户端收到的就是「SSE注释+JSON」
// 混合体且响应头已发出——实测触发 AiCode 端「服务器返回的内容不是有效的 JSON」
// （压缩请求思考间隙 >15s 必现）。
func proxyStream(w http.ResponseWriter, resp *http.Response, clientStreaming bool, transform func(*chatChunk) []byte) error {
	defer resp.Body.Close()
	flusher, _ := w.(http.Flusher)
	scanner := bufio.NewScanner(resp.Body)
	scanner.Buffer(make([]byte, 0, 64*1024), 8<<20)

	// 心跳：扫描是阻塞的，不能用超时打断它（会把上游连接读断）。
	// 用一个每秒检查的看门狗，静默超过阈值就插一行 SSE 注释。
	// 长思考模型（DeepSeek 等）输出间隙可达几十秒，没有心跳会被中间代理或客户端掐断。
	//
	// 看门狗与主循环是两个协程，都会写同一个 w，而 http.ResponseWriter 并非并发安全：
	// 裸写会交错出畸形 SSE（两行 data: 串在一起），客户端解析失败，往往表现为
	// 「回答说到一半就断了」。同时 lastWrite 也被两边读写，是真正的数据竞争。
	// 所以把「写响应 + 刷新 + 记时间」收进一个带锁的闭包，两个协程统一走它。
	var (
		writeMu   sync.Mutex
		lastWrite = time.Now()
	)
	writeOut := func(b []byte) error {
		writeMu.Lock()
		defer writeMu.Unlock()
		if _, err := w.Write(b); err != nil {
			return err
		}
		if flusher != nil {
			flusher.Flush()
		}
		lastWrite = time.Now()
		return nil
	}
	// beat 在静默超阈值时补一行心跳；返回 false 表示写失败，看门狗可以收工了。
	// 非流式客户端（clientStreaming=false）绝不写心跳：响应体必须是纯 JSON，
	// 心跳字节混入后客户端解析必然失败（且响应头已发出，无法挽回）。
	beat := func() bool {
		if !clientStreaming {
			return true
		}
		writeMu.Lock()
		defer writeMu.Unlock()
		if time.Since(lastWrite) < sseKeepaliveInterval {
			return true
		}
		if _, err := w.Write([]byte(sseKeepalive)); err != nil {
			return false
		}
		if flusher != nil {
			flusher.Flush()
		}
		lastWrite = time.Now()
		return true
	}
	stopBeat := make(chan struct{})
	defer close(stopBeat)
	go func() {
		// 检查周期由心跳间隔推导，不能写死了秒：
		// 写死的话，间隔一旦被调到小于 1 秒，看门狗永远来不及看，
		// 心跳就静默失效了。上限仍是一秒（生产间隔 15 秒 → 1 秒检查一次，
		// 与原来行为一致），下限防呆避免拿到 0 周期空转。
		period := sseKeepaliveInterval / 4
		if period <= 0 {
			period = time.Millisecond
		}
		if period > time.Second {
			period = time.Second
		}
		ticker := time.NewTicker(period)
		defer ticker.Stop()
		for {
			select {
			case <-stopBeat:
				return
			case <-ticker.C:
				if !beat() {
					return
				}
			}
		}
	}()

	for scanner.Scan() {
		line := scanner.Text()
		if !strings.HasPrefix(line, "data:") {
			continue
		}
		payload := strings.TrimSpace(strings.TrimPrefix(line, "data:"))
		if payload == "" || payload == "[DONE]" {
			if payload == "[DONE]" {
				if out := transform(nil); out != nil {
					if err := writeOut(out); err != nil {
						return err
					}
				}
			}
			continue
		}
		var chunk chatChunk
		if err := json.Unmarshal([]byte(payload), &chunk); err != nil {
			continue
		}
		if out := transform(&chunk); out != nil {
			if err := writeOut(out); err != nil {
				return err
			}
		}
	}
	return scanner.Err()
}

func sseData(v any) []byte {
	b, _ := json.Marshal(v)
	return []byte("data: " + string(b) + "\n\n")
}
