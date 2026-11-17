// Package odohclient wraps odoh-go's HPKE and wire format for gomobile binding.
// Networking is handled by Kotlin/OkHttp so Android's system TLS trust store applies.
package odohclient

import (
	"errors"

	odoh "github.com/cloudflare/odoh-go"
)

// paddingBlock is the size the plaintext DNS message is padded up to a multiple of
// (RFC 8467 recommends 128; RFC 9230 section 6.3 asks for padding so the ciphertext length leaks
// less about the name). paddingHeader mirrors RethinkDNS' xdns.ComputePaddingSize, which counts
// the 4-byte padding option header, so both clients produce the same sizes.
const (
	paddingBlock  = 128
	paddingHeader = 4
)

func paddingFor(n int) uint16 {
	return uint16((paddingBlock - (n+paddingHeader)%paddingBlock) % paddingBlock)
}

// Session holds every ODoH config the Target advertises. Targets that rotate keys publish
// several, and a client that only knows the first one gets "unknown key id" for the rest.
type Session struct {
	configs []odoh.ObliviousDoHConfig
}

func NewSession(configBlob []byte) (*Session, error) {
	if len(configBlob) == 0 {
		return nil, errors.New("empty config blob")
	}
	configs, err := odoh.UnmarshalObliviousDoHConfigs(configBlob)
	if err != nil {
		return nil, err
	}
	if len(configs.Configs) == 0 {
		return nil, errors.New("no configs in blob")
	}
	return &Session{configs: configs.Configs}, nil
}

type QueryContext struct {
	message []byte
	decrypt func([]byte) ([]byte, error)
}

// Count is the number of configs the Target advertised.
func (s *Session) Count() int {
	return len(s.configs)
}

// EncryptQuery encrypts with the first advertised config.
func (s *Session) EncryptQuery(dnsQuery []byte) (*QueryContext, error) {
	return s.EncryptQueryAt(dnsQuery, 0)
}

// EncryptQueryAt encrypts with the config at index (0 <= index < Count).
func (s *Session) EncryptQueryAt(dnsQuery []byte, index int) (*QueryContext, error) {
	if len(dnsQuery) < 12 {
		return nil, errors.New("dns query too short")
	}
	if index < 0 || index >= len(s.configs) {
		return nil, errors.New("config index out of range")
	}
	q := odoh.CreateObliviousDNSQuery(dnsQuery, paddingFor(len(dnsQuery)))
	msg, ctx, err := s.configs[index].Contents.EncryptQuery(q)
	if err != nil {
		return nil, err
	}

	return &QueryContext{
		message: msg.Marshal(),
		decrypt: func(response []byte) ([]byte, error) {
			if len(response) == 0 {
				return nil, errors.New("empty response")
			}
			resp, err := odoh.UnmarshalDNSMessage(response)
			if err != nil {
				return nil, err
			}
			answer, err := ctx.OpenAnswer(resp)
			if err != nil {
				return nil, err
			}
			return answer, nil
		},
	}, nil
}

func (q *QueryContext) Message() []byte {
	return append([]byte(nil), q.message...)
}

func (q *QueryContext) DecryptResponse(response []byte) ([]byte, error) {
	if q == nil || q.decrypt == nil {
		return nil, errors.New("invalid query context")
	}
	return q.decrypt(response)
}
