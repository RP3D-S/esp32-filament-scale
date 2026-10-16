#pragma once

#include <stddef.h>
#include <stdint.h>
#include <string.h>

// Used only by the cloud task. Cache IPv4 answers for at most their DNS TTL.
class FbDnsCache {
    struct Entry {
        char host[128] = {};
        uint32_t address = 0, storedAt = 0, lifetimeMs = 0;
    };
    Entry entries[6];
    size_t next = 0;
    uint32_t network[4] = {}, preferredDns = 0;
    uint8_t accessPoint[6] = {};
    bool networkKnown = false;

public:
    void clear() {
        for (auto &entry : entries) entry = Entry();
        next = 0;
        preferredDns = 0;
        networkKnown = false;
    }

    void setNetwork(uint32_t ip, uint32_t gateway, uint32_t dns0, uint32_t dns1,
                    const uint8_t *bssid) {
        const uint32_t current[] = {ip, gateway, dns0, dns1};
        const uint8_t empty[6] = {};
        if (!bssid) bssid = empty;
        if (!networkKnown || memcmp(network, current, sizeof(network)) ||
            memcmp(accessPoint, bssid, sizeof(accessPoint))) {
            clear();
            memcpy(network, current, sizeof(network));
            memcpy(accessPoint, bssid, sizeof(accessPoint));
            networkKnown = true;
        }
    }

    bool lookup(const char *host, uint32_t now, uint32_t &address) const {
        for (const auto &entry : entries) {
            if (entry.lifetimeMs && !strcmp(entry.host, host) &&
                (uint32_t)(now - entry.storedAt) < entry.lifetimeMs) {
                address = entry.address;
                return true;
            }
        }
        return false;
    }

    void forget(const char *host) {
        for (auto &entry : entries) {
            if (!strcmp(entry.host, host)) entry.lifetimeMs = 0;
        }
    }

    void remember(const char *host, uint32_t address, uint32_t ttlSeconds, uint32_t now) {
        forget(host);
        const size_t length = strlen(host);
        if (!ttlSeconds || !address || length >= sizeof(entries[0].host)) return;
        Entry &entry = entries[next];
        next = (next + 1) % (sizeof(entries) / sizeof(entries[0]));
        memcpy(entry.host, host, length + 1);
        entry.address = address;
        entry.storedAt = now;
        entry.lifetimeMs = (ttlSeconds < 300 ? ttlSeconds : 300) * 1000;
    }

    uint32_t preferred() const { return preferredDns; }
    void prefer(uint32_t dns) { preferredDns = dns; }
};
