#include "proxy.hpp"
#include <uv_lwip.hpp>
#include <base/llog.h>
#include <functional>
#include <memory>
#include <cstring>
#include "gateway.h"
#include "helper.h"
#include "packet.h"
#include "ipv4/ipv4.h"


namespace slp {

static const char kConnTestHttp[] = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nX-Organization: Nintendo\r\n\r\nok";
static const uint8_t kConnTestIp[4] = {192, 0, 2, 1};

static bool dns_qname_matches(const uint8_t *qname, const uint8_t *end, const char *dotted)
{
    const uint8_t *p = qname;
    const char *d = dotted;
    for (;;) {
        if (p >= end) return false;
        uint8_t lab = *p++;
        if (lab == 0) return *d == '\0';
        if (lab & 0xC0) return false;
        if (p + lab > end) return false;
        for (uint8_t i = 0; i < lab; i++) {
            char a = (char)p[i];
            char b = d[i];
            if (b == '\0') return false;
            if (a >= 'A' && a <= 'Z') a += 'a' - 'A';
            if (b >= 'A' && b <= 'Z') b += 'a' - 'A';
            if (a != b) return false;
        }
        p += lab;
        d += lab;
        if (*d == '.') d++;
        else if (*d != '\0') return false;
    }
}

static bool conntest_dns_reply(struct packet_ctx *pctx, const uint8_t src[4], uint16_t srcport,
        const uint8_t dns_ip[4], const uint8_t *query, uint16_t qlen)
{
    if (qlen < 12) return false;
    if (query[2] & 0x80) return false;
    if (query[4] != 0 || query[5] != 1) return false;
    const uint8_t *qname = query + 12;
    const uint8_t *end = query + qlen;
    const uint8_t *p = qname;
    while (p < end && *p != 0) {
        if (*p & 0xC0) return false;
        p += 1 + *p;
    }
    if (p + 5 > end) return false;
    if (p[1] != 0 || p[2] != 1) return false;
    if (p[3] != 0 || p[4] != 1) return false;
    if (!dns_qname_matches(qname, end, "conntest.nintendowifi.net") &&
        !dns_qname_matches(qname, end, "ctest.cdn.nintendo.net")) {
        return false;
    }
    uint8_t resp[2048];
    if ((size_t)qlen + 16 > sizeof(resp)) return false;
    memcpy(resp, query, qlen);
    resp[2] |= 0x80;
    resp[3] |= 0x80;
    resp[3] &= 0xF0;
    resp[6] = 0;
    resp[7] = 1;
    resp[8] = 0;
    resp[9] = 0;
    resp[10] = 0;
    resp[11] = 0;
    uint8_t *a = resp + qlen;
    a[0] = 0xC0; a[1] = 0x0C;
    a[2] = 0; a[3] = 1;
    a[4] = 0; a[5] = 1;
    a[6] = 0; a[7] = 0; a[8] = 0; a[9] = 60;
    a[10] = 0; a[11] = 4;
    memcpy(a + 12, kConnTestIp, 4);
    struct payload part;
    part.ptr = resp;
    part.len = qlen + 16;
    part.next = NULL;
    send_udp_ex(pctx, dns_ip, 53, src, srcport, &part);
    return true;
}

class TcpConnection: public lwip::IConnection {
    protected:
        std::shared_ptr<IProxyTcp> ptcp;
        bool closing;
        bool conntest;
    public:
        TcpConnection(std::shared_ptr<lwip::UvLwipBase> lwip, std::shared_ptr<IProxyTcp> ptcp) : lwip::IConnection(lwip), ptcp(ptcp), closing(false), conntest(false) {
        }
        virtual void onConnection() override {
            sockaddr_in local = this->getLocalAddr();
            if (local.sin_port == htons(80) && memcmp(&local.sin_addr, kConnTestIp, 4) == 0) {
                this->conntest = true;
                this->write(kConnTestHttp, strlen(kConnTestHttp));
                return;
            }
            LLOG(LLOG_DEBUG, "TcpConnection::onConnection");

            this->ptcp->onData([this](uvw::DataEvent &e) {
                ptcp->stop();
                this->write(std::move(e.data), e.length);
            });
            this->ptcp->onWrite([this](uvw::WriteEvent &e) {
                this->read();
            });
            this->ptcp->onClose([&](uvw::ErrorEvent &e) {
                LLOG(LLOG_DEBUG, "ptcp->onClose %d %s", e.code(), e.what());
                this->kill();
            });

            this->ptcp->onConnect([&](uvw::ConnectEvent &e) {
                LLOG(LLOG_DEBUG, "ptcp->onConnect");
                ptcp->read();
                this->read();
            });
            sockaddr_in addr = this->getLocalAddr();
            this->ptcp->connect(*reinterpret_cast<sockaddr*>(&addr));
        };
        virtual void onData(std::unique_ptr<char []> data, unsigned int len) override {
            if (this->conntest) {
                return;
            }
            this->stop();
            ptcp->write(std::move(data), len);
        };
        virtual void onWrite() override {
            if (this->conntest) {
                this->close();
                return;
            }
            ptcp->read();
        }
        virtual void onClose() override {
            LLOG(LLOG_DEBUG, "lwip->onClose");
            this->kill();
        };
        virtual void onClosed() override {
            this->kill();
        }
        void kill() {
            if (!this->closing) {
                this->closing = true;
                ptcp->stop();
                this->stop();
                ptcp->close();
                this->close();
            }
        }
};
class SwitchFakeTcpConnection: public lwip::IConnection {
    protected:
    public:
        SwitchFakeTcpConnection(std::shared_ptr<lwip::UvLwipBase> lwip) : lwip::IConnection(lwip) {

        }
        virtual void onConnection() override {
            this->write(kConnTestHttp, strlen(kConnTestHttp));
        };
        virtual void onWrite() override {
            this->close();
        }
        virtual void onData(std::unique_ptr<char []> data, unsigned int len) override {
        };
        virtual void onClose() override {
        };
        virtual void onClosed() override {
        };
};

class UvLwip : public lwip::UvLwipBase {
    protected:
        std::shared_ptr<slp::IProxy> proxy;
        struct packet_ctx *packet_ctx;
        virtual int uvlOutput(void *buffer, uint32_t len) override {
            return lan_play_gateway_send_packet(this->packet_ctx, buffer, len);
        };
        virtual std::shared_ptr<lwip::IConnection> newConnection(std::shared_ptr<UvLwipBase> self) override {
            if (this->proxy) {
                return std::make_shared<TcpConnection>(shared_from_this(), this->proxy->newTcp());
            } else {
                return std::shared_ptr<lwip::IConnection>(nullptr);
            }
        };
    public:
        UvLwip(uv_loop_t *loop, struct packet_ctx *packet_ctx, std::shared_ptr<slp::IProxy> proxy)
            : lwip::UvLwipBase(loop), proxy(proxy), packet_ctx(packet_ctx) {
        }
};

class FakeInternetUvLwip : public lwip::UvLwipBase {
    protected:
        struct packet_ctx *packet_ctx;
        virtual int uvlOutput(void *buffer, uint32_t len) override {
            return lan_play_gateway_send_packet(this->packet_ctx, buffer, len);
        };
        virtual std::shared_ptr<lwip::IConnection> newConnection(std::shared_ptr<UvLwipBase> self) override {
            return std::make_shared<SwitchFakeTcpConnection>(shared_from_this());
        };
    public:
        FakeInternetUvLwip(uv_loop_t *loop, struct packet_ctx *packet_ctx) : lwip::UvLwipBase(loop), packet_ctx(packet_ctx) {
        }
};

}

struct gateway {
    protected:
        struct packet_ctx *packet_ctx;
        std::shared_ptr<slp::IProxy> proxy;
        std::shared_ptr<slp::lwip::UvLwipBase> uvlwip;
    public:
        gateway(struct packet_ctx *packet_ctx) : packet_ctx(packet_ctx) {}
        ~gateway() {
            LLOG(LLOG_DEBUG, "~gateway");
            uvlwip->release();
        }
        void setProxy(const struct slp_addr_in *socks5_proxy_addr, const char *username, const char *password) {
            if (socks5_proxy_addr) {
                this->proxy = slp::IProxy::initSocks5(uvw::Loop::getDefault(), packet_ctx, socks5_proxy_addr, username, password);
            } else {
                this->proxy = slp::IProxy::initDirect(uvw::Loop::getDefault(), packet_ctx);
            }
        }
        void initLwip(bool fake_internet) {
            if (fake_internet) {
                this->uvlwip = std::make_shared<slp::FakeInternetUvLwip>(packet_ctx->arg->loop, packet_ctx);
            } else {
                this->uvlwip = std::make_shared<slp::UvLwip>(packet_ctx->arg->loop, packet_ctx, this->proxy);
            }
            this->uvlwip->init();
        }
        int processUdp(const uint8_t *data, int data_len) {
            uint8_t ip_version = 0;
            if (data_len > 0) {
                ip_version = (data[0] >> 4);
            }

            if (ip_version == 4) {
                // ignore non-UDP packets
                if (data_len < IPV4_OFF_END || data[IPV4_OFF_PROTOCOL] != IPV4_PROTOCOL_UDP) {
                    return -1;
                }
                uint16_t ipv4_header_len = (data[0] & 0xF) * 4;
                const uint8_t *udp_base = data + ipv4_header_len;
                uint8_t src[4];
                uint8_t dst[4];
                uint16_t srcport;
                uint16_t dstport;
                const void *payload;
                uint16_t len;

                CPY_IPV4(src, data + IPV4_OFF_SRC);
                CPY_IPV4(dst, data + IPV4_OFF_DST);
                srcport = READ_NET16(udp_base, UDP_OFF_SRCPORT);
                dstport = READ_NET16(udp_base, UDP_OFF_DSTPORT);
                payload = udp_base + UDP_OFF_END;
                len = data_len - ipv4_header_len - UDP_OFF_END;

                if (dstport == 53 && (int)ipv4_header_len + UDP_OFF_END <= data_len &&
                    slp::conntest_dns_reply(this->packet_ctx, src, srcport, dst, (const uint8_t *)payload, len)) {
                    return 0;
                }
                this->proxy->udpSend(src, srcport, dst, dstport, payload, len);
                return 0;
            }

            return -1;
        }
        void onPacket(const uint8_t *data, int data_len) {
            if (this->processUdp(data, data_len) == 0) {
                return;
            }

            uv_buf_t b;

            b.base = (char *)data;
            b.len = data_len;

            this->uvlwip->input(b);
        }
};

int gateway_init(struct gateway **gw, struct packet_ctx *packet_ctx, bool fake_internet, struct slp_addr_in *socks5_proxy_addr, const char *username, const char *password) {
    *gw = new gateway(packet_ctx);
    (*gw)->setProxy(socks5_proxy_addr, username, password);
    (*gw)->initLwip(fake_internet);
    return 0;
}
int gateway_close(struct gateway *gateway) {
    delete gateway;
    return 0;
}

void gateway_on_packet(struct gateway *gateway, const uint8_t *data, int data_len) {
    data += ETHER_OFF_END;
    data_len -= ETHER_OFF_END;
    gateway->onPacket(data, data_len);
}
