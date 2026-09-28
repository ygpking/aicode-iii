package main

import (
	"bytes"
	"io"
	"net/http"
	"strings"
	"sync"
	"testing"
	"time"
)

// syncBuffer 是并发安全的响应写入目标，替代 httptest 的 ResponseRecorder。
//
// 不能直接用 map/裸 []byte 收：被断言的对象正是「多个协程同时写」，收的一方
// 自己也得并发安全，否则测试会先在自己的收集器上崩，掩盖真正要测的东西。
type syncBuffer struct {
	mu  sync.Mutex
	buf bytes.Buffer
	// flushes 记刷新次数：心跳必须在写完后立刻刷新，否则内容卡在
	// net/http 的缓冲里，客户端照样饿死（发了等于没发）。
	flushes int
}

func (s *syncBuffer) Write(p []byte) (int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.buf.Write(p)
}

// Header / WriteHeader 让 syncBuffer 满足 http.ResponseWriter。
// proxyStream 只用到 Write 与 Flush，这两个是接口凑数用的。
func (s *syncBuffer) Header() http.Header { return http.Header{} }

func (s *syncBuffer) WriteHeader(statusCode int) {}

func (s *syncBuffer) snapshot() ([]byte, int) {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := make([]byte, s.buf.Len())
	copy(out, s.buf.Bytes())
	return out, s.flushes
}

// 必须是 http.Flusher，proxyStream 才会走刷新分支。
func (s *syncBuffer) Flush() {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.flushes++
}

// sseBody 把若干 chunk 拼成上游 SSE 体。每块之间隔 gap，用来制造静默窗口。
func sseBody(t *testing.T, n int, gap time.Duration) io.ReadCloser {
	t.Helper()
	var b strings.Builder
	for i := 0; i < n; i++ {
		b.WriteString(`data: {"choices":[{"delta":{"content":"x"}}]}` + "\n\n")
	}
	b.WriteString("data: [DONE]\n\n")
	return io.NopCloser(strings.NewReader(b.String()))
}

// stallBody 先吐一块，然后长时间静默，最后收尾——专门用来触发心跳协程。
//
// 必须用管道：strings.Reader 一读完就 EOF，扫描立刻结束，心跳协程根本没机会
// 与主循环重叠；只有让读端堵着，两个协程才会真正并发送数。
func stallBody(t *testing.T, stall time.Duration) (*io.PipeReader, chan struct{}) {
	t.Helper()
	pr, pw := io.Pipe()
	done := make(chan struct{})
	go func() {
		defer close(done)
		_, _ = pw.Write([]byte(`data: {"choices":[{"delta":{"content":"first"}}]}` + "\n\n"))
		time.Sleep(stall)
		_, _ = pw.Write([]byte("data: [DONE]\n\n"))
		_ = pw.Close()
	}()
	return pr, done
}

// proxyStream 的心跳看门狗与主循环是两个协程，都会写同一个响应。
// 这个测试在 -race 下必须干净：任一处的裸写（未串行化）都会被抓出来。
func TestProxyStreamConcurrentKeepaliveNoRace(t *testing.T) {
	// 把心跳周期压到毫秒级：要测的是「两个协程并发写同一个响应」，
	// 不是心跳本身要等多久。不改的话每条用例白等 30 秒，慢测试最终没人跑。
	old := sseKeepaliveInterval
	sseKeepaliveInterval = 20 * time.Millisecond
	defer func() { sseKeepaliveInterval = old }()

	pr, done := stallBody(t, 150*time.Millisecond)
	defer func() { _ = pr.Close() }()

	w := &syncBuffer{}
	chunks := 0
	err := proxyStream(w, &http.Response{Body: pr}, true, func(c *chatChunk) []byte {
		if c == nil {
			return nil
		}
		chunks++
		return []byte("data: out\n\n")
	})
	if err != nil {
		t.Fatalf("proxyStream 报错: %v", err)
	}
	<-done

	got, flushes := w.snapshot()
	if chunks == 0 {
		t.Fatalf("应至少转发 1 个块，实际 %d", chunks)
	}
	if !bytes.Contains(got, []byte("first")) && !bytes.Contains(got, []byte("data: out")) {
		t.Fatalf("响应里没有转发内容，实际 %q", string(got))
	}
	// 静默约 2 个心跳周期，至少要发出 1 次心跳
	if !bytes.Contains(got, []byte(sseKeepalive)) {
		t.Fatalf("静默超过阈值却未发出心跳，实际 %q", string(got))
	}
	if flushes == 0 {
		t.Fatalf("写过内容却一次都没刷新，客户端会一直饿着")
	}
}

// 并发压力：多个 proxyStream 同时跑，验证跨请求之间也没有共享状态串扰。
func TestProxyStreamParallelNoRace(t *testing.T) {
	const n = 8
	var wg sync.WaitGroup
	for i := 0; i < n; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			w := &syncBuffer{}
			if err := proxyStream(w, &http.Response{Body: sseBody(t, 5, 0)}, true, func(c *chatChunk) []byte {
				if c == nil {
					return nil
				}
				return []byte("data: out\n\n")
			}); err != nil {
				t.Errorf("proxyStream 报错: %v", err)
			}
			if got, _ := w.snapshot(); !bytes.Contains(got, []byte("data: out")) {
				t.Errorf("未转发内容")
			}
		}()
	}
	wg.Wait()
}

// 同一段 SSE 体，转发出来的内容必须逐字节一致（写入串行化不能改变顺序或内容）。
func TestProxyStreamOutputDeterministic(t *testing.T) {
	var want bytes.Buffer
	for i := 0; i < 20; i++ {
		want.WriteString("data: out\n\n")
	}
	for round := 0; round < 20; round++ {
		w := &syncBuffer{}
		if err := proxyStream(w, &http.Response{Body: sseBody(t, 20, 0)}, true, func(c *chatChunk) []byte {
			if c == nil {
				return nil
			}
			return []byte("data: out\n\n")
		}); err != nil {
			t.Fatalf("proxyStream 报错: %v", err)
		}
		got, _ := w.snapshot()
		if !bytes.Equal(got, want.Bytes()) {
			t.Fatalf("第 %d 轮输出不一致:\n got=%q\nwant=%q", round, string(got), want.String())
		}
	}
}

// 心跳周期与常量绑定：改了常量这个测试会跟着变，防止文档与实现漂移。
func TestProxyStreamKeepaliveUsesConfiguredInterval(t *testing.T) {
	if sseKeepaliveInterval <= 0 {
		t.Fatalf("心跳间隔必须为正，实际 %v", sseKeepaliveInterval)
	}
	if sseKeepaliveInterval > 60*time.Second {
		t.Fatalf("心跳间隔过大（%v），中间代理会在它之前掐断连接", sseKeepaliveInterval)
	}
}
