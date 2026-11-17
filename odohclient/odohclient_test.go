package odohclient

import "testing"

func TestPaddingFor(t *testing.T) {
	for _, n := range []int{12, 40, 127, 128, 129, 255, 512} {
		if (n+int(paddingFor(n)))%paddingBlock != 0 {
			t.Fatalf("n=%d not padded to a block multiple", n)
		}
	}
	if paddingFor(128) != 0 {
		t.Fatal("exact block must not add padding")
	}
}
