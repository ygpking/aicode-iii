package main

import (
	"bytes"
	"io"
	"net/http"
	"testing"
	"time"
)

// 非流式客户端（clientStreaming=false）的心跳必须被完全禁用：
// 聚合完成后响应体只能是 writeJSON 追加的纯 JSON，若中途写入 SSE 心跳，
// 客户端（AiCode 等以非流式调压缩的客户端）会因「SSE注释+JSON 混合体」解析失败。
// 回归背景：压缩请求思考间隙 >15s 时必现「服务器返回的内容不是有效的 JSON」。
func TestProxyStreamNoKeepaliveForNonStreaming(t *testing.T) {
	old := sseKeepaliveInterval
	// 缩短心跳间隔，让静默窗口内必然触发多次心跳判断；测试结束还原。
	sseKeepaliveInterval = 30 * time.Millisecond
	defer func() { sseKeepaliveInterval = old }()

	pr, done := stallBody(t, 120*time.Millisecond)
	defer func() { _ = pr.Close() }()

	w := &syncBuffer{}
	err := proxyStream(w, &http.Response{Body: pr}, false, func(c *chatChunk) []byte {
		if c == nil {
			return nil
		}
		return nil // 非流式聚合：transform 不直接输出，最终由调用方 writeJSON
	})
	if err != nil {
		t.Fatalf("proxyStream 报错: %v", err)
	}
	<-done

	got, _ := w.snapshot()
	if bytes.Contains(got, []byte(sseKeepalive)) {
		t.Fatalf("非流式路径不应写入心跳，实际响应体: %q", string(got))
	}
	if len(got) != 0 {
		t.Fatalf("非流式聚合期间不应有任何字节进入响应体，实际 %q", string(got))
	}
}

// 对照组：同样条件下 clientStreaming=true 必须照常发心跳（防止把流式心跳一起禁了）。
func TestProxyStreamKeepaliveStillWorksWhenStreaming(t *testing.T) {
	old := sseKeepaliveInterval
	sseKeepaliveInterval = 30 * time.Millisecond
	defer func() { sseKeepaliveInterval = old }()

	pr, done := stallBody(t, 120*time.Millisecond)
	defer func() { _ = pr.Close() }()

	w := &syncBuffer{}
	err := proxyStream(w, &http.Response{Body: pr}, true, func(c *chatChunk) []byte {
		if c == nil {
			return nil
		}
		return []byte("data: out\n\n")
	})
	if err != nil {
		t.Fatalf("proxyStream 报错: %v", err)
	}
	<-done

	got, _ := w.snapshot()
	if !bytes.Contains(got, []byte(sseKeepalive)) {
		t.Fatalf("流式路径静默超阈值应发出心跳，实际 %q", string(got))
	}
}

var _ io.Reader = (*io.PipeReader)(nil)
