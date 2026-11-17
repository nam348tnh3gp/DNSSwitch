module github.com/georgechou0517/dnsswitch-odoh

go 1.24

// Same odoh-go release as RethinkDNS' firestack (HPKE via cisco/go-hpke). The 2023 pseudo-version
// that was pinned before moved to circl HPKE; deployed Targets built on v1.0.0 may reject its
// key ids with "unknown key id".
require github.com/nam348tnh3gp/odoh-go v0.0.4