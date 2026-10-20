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
