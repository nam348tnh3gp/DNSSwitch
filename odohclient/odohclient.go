// Package odohclient wraps odoh-go's HPKE and wire format for gomobile binding.
// Networking is handled by Kotlin/OkHttp so Android's system TLS trust store applies.
package odohclient

import (
	"errors"

	odoh "github.com/cloudflare/odoh-go"
)

type Session struct {
	config odoh.ObliviousDoHConfig
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
	return &Session{config: configs.Configs[0]}, nil
}

type QueryContext struct {
	message []byte
	decrypt func([]byte) ([]byte, error)
}

func (s *Session) EncryptQuery(dnsQuery []byte) (*QueryContext, error) {
	if len(dnsQuery) < 12 {
		return nil, errors.New("dns query too short")
	}
	q := odoh.CreateObliviousDNSQuery(dnsQuery, 0)
	msg, ctx, err := s.config.Contents.EncryptQuery(q)
	if err != nil {
		return nil, err
	}

	// ODoH intentionally uses a placeholder DNS transaction ID on the wire.
	// Restore the caller's original ID after decrypting so the local DNS stack
	// can match the response to its outstanding query.
	queryID := [2]byte{dnsQuery[0], dnsQuery[1]}
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
			answer, err := ctx.DecryptResponse(resp)
			if err != nil {
				return nil, err
			}
			if len(answer) < 12 {
				return nil, errors.New("decrypted DNS response too short")
			}
			answer[0] = queryID[0]
			answer[1] = queryID[1]
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
