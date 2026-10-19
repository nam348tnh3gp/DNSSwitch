// Package odohclient wraps odoh-go's HPKE + wire format for gomobile binding.
// No networking — Kotlin/OkHttp handles HTTPS + system trust store.
package odohclient

import (
	"errors"

	odoh "github.com/cloudflare/odoh-go"
)

// Session holds a parsed ODoH config (resolver public key etc.)
type Session struct {
	config odoh.ObliviousDoHConfig
}

// NewSession parses blob fetched from <target>/.well-known/odohconfigs.
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

// QueryContext holds per-query HPKE context (needed for response decryption).
type QueryContext struct {
	ctx odoh.ObliviousDoHContext
	msg odoh.ObliviousDoHMessage
}

// EncryptQuery encrypts a raw DNS wire query.
func (s *Session) EncryptQuery(dnsQuery []byte) (*QueryContext, error) {
	if len(dnsQuery) < 12 {
		return nil, errors.New("dns query too short")
	}
	q := odoh.CreateObliviousDNSQuery(dnsQuery, 0)
	msg, ctx, err := s.config.Contents.EncryptQuery(q)
	if err != nil {
		return nil, err
	}
	return &QueryContext{ctx: ctx, msg: msg}, nil
}

// Message returns marshaled bytes to POST to proxy.
func (q *QueryContext) Message() []byte {
	return q.msg.Marshal()
}

// DecryptResponse decrypts proxy response → raw DNS wire answer.
func (q *QueryContext) DecryptResponse(response []byte) ([]byte, error) {
	if len(response) == 0 {
		return nil, errors.New("empty response")
	}
	resp, err := odoh.UnmarshalDNSMessage(response)
	if err != nil {
		return nil, err
	}
	answer, err := q.ctx.DecryptResponse(resp)
	if err != nil {
		return nil, err
	}
	return answer.DnsMessage, nil
}