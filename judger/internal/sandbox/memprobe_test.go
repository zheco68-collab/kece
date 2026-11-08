package sandbox

import (
	"strings"
	"testing"
)

// TestReadMemProbe 覆盖探测文件的解析：只有能读出正数才当作测到，其余一律 nil（不伪造成 0）。
func TestReadMemProbe(t *testing.T) {
	tests := []struct {
		name    string
		content string
		wantKb  int // -1 表示期望 nil
	}{
		{"正常数值", "70254592\n", 68608},
		{"无换行", "20480", 20},
		{"零", "0", -1},
		{"空文件", "", -1},
		{"非数字", "N/A", -1},
		{"负数", "-1024", -1},
		{"不足 1KB", "512", -1},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got := readMemProbe(memProbePrefix + tt.content)
			if tt.wantKb < 0 {
				if got != nil {
					t.Fatalf("readMemProbe(%q) = %d, 期望 nil", tt.content, *got)
				}
				return
			}
			if got == nil {
				t.Fatalf("readMemProbe(%q) = nil, 期望 %d", tt.content, tt.wantKb)
			}
			if *got != tt.wantKb {
				t.Fatalf("readMemProbe(%q) = %d, 期望 %d", tt.content, *got, tt.wantKb)
			}
		})
	}

	if got := readMemProbe("no measurement\n"); got != nil {
		t.Fatalf("未测量时应返回 nil，得到 %d", *got)
	}
}

// TestRunCommandWrapsMemoryProbe checks that the supervisor owns stderr and timeout.
func TestRunCommandWrapsMemoryProbe(t *testing.T) {
	for _, lang := range []string{"cpp", "java", "python", "arkts"} {
		cmd := runCommand(lang, "1.000")
		if !strings.Contains(cmd, "code=$?") || !strings.HasSuffix(cmd, "exit $code") {
			t.Errorf("%s: 未保留程序退出码: %s", lang, cmd)
		}
		probeAt := strings.Index(cmd, memProbePrefix)
		if probeAt < strings.Index(cmd, "timeout") ||
			!strings.Contains(cmd, "nobody -c") || !strings.Contains(cmd, "2>/dev/null; code=$?") ||
			strings.Contains(cmd, "/work/input.txt") || strings.Contains(cmd, "/work/.memory") {
			t.Errorf("%s: 标准输入或内存探测隔离不正确: %s", lang, cmd)
		}
	}
	if got := runCommand("brainfuck", "1.000"); got != "exit 1" {
		t.Errorf("未知语言应直接失败，得到 %q", got)
	}
}

func TestLimitedBufferBoundsOutput(t *testing.T) {
	lb := &limitedBuffer{max: 16}
	chunk := []byte("0123456789abcdefXYZ")
	n, err := lb.Write(chunk)
	if err != nil || n != len(chunk) {
		t.Fatalf("Write 返回 (%d, %v), 期望 (%d, nil)", n, err, len(chunk))
	}
	if got := lb.String(); got != "0123456789abcdef" {
		t.Fatalf("limitedBuffer = %q, 期望截断为 16 字节", got)
	}
	// 继续写入超量数据应静默丢弃，不再增长缓冲内存
	_, _ = lb.Write([]byte("more-runaway-output"))
	if len(lb.String()) != 16 {
		t.Fatalf("limitedBuffer 长度 = %d, 期望恒为 16", len(lb.String()))
	}
}

func TestReadMemProbeRejectsOversizedValue(t *testing.T) {
	if got := readMemProbe(memProbePrefix + strings.Repeat("9", 100)); got != nil {
		t.Fatalf("超大文件应返回 nil，得到 %d", *got)
	}
}
