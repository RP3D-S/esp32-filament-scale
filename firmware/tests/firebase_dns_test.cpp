#include "../src/firebase_dns.h"

#include <assert.h>
#include <stdio.h>

int main() {
    FbDnsCache cache;
    const uint8_t ap[] = {1, 2, 3, 4, 5, 6};
    cache.setNetwork(1, 2, 3, 4, ap);
    uint32_t address = 0;

    // Reuse a live answer, but never beyond the server's TTL.
    cache.remember("firestore.googleapis.com", 123, 60, 1000);
    assert(cache.lookup("firestore.googleapis.com", 60999, address) && address == 123);
    assert(!cache.lookup("firestore.googleapis.com", 61000, address));
    assert(!cache.lookup("other.googleapis.com", 1001, address));

    // A zero TTL replaces any previous answer without caching the new one.
    cache.remember("firestore.googleapis.com", 456, 60, 1000);
    cache.remember("firestore.googleapis.com", 789, 0, 1001);
    assert(!cache.lookup("firestore.googleapis.com", 1002, address));

    // Conservative expiry also handles large TTLs and the millis() wrap.
    cache.remember("firestore.googleapis.com", 123, UINT32_MAX, 1000);
    assert(cache.lookup("firestore.googleapis.com", 300999, address));
    assert(!cache.lookup("firestore.googleapis.com", 301000, address));
    cache.remember("firestore.googleapis.com", 123, 1, UINT32_MAX - 499);
    assert(cache.lookup("firestore.googleapis.com", 499, address));
    assert(!cache.lookup("firestore.googleapis.com", 500, address));

    // Keep answers on the same network; invalidate on IP/router/DNS/AP changes.
    for (unsigned field = 0; field < 5; ++field) {
        cache.setNetwork(1, 2, 3, 4, ap);
        cache.remember("firestore.googleapis.com", 123, 60, 1000);
        cache.prefer(99);
        cache.setNetwork(1, 2, 3, 4, ap);
        assert(cache.lookup("firestore.googleapis.com", 1001, address));
        assert(cache.preferred() == 99);
        uint32_t network[] = {1, 2, 3, 4};
        uint8_t otherAp[] = {1, 2, 3, 4, 5, 6};
        if (field < 4) network[field] += 10;
        else otherAp[5] = 7;
        cache.setNetwork(network[0], network[1], network[2], network[3], otherAp);
        assert(!cache.lookup("firestore.googleapis.com", 1001, address));
        assert(cache.preferred() == 0);
    }

    // Forget a failed connection without losing another host's valid answer.
    cache.remember("firestore.googleapis.com", 123, 60, 1000);
    cache.remember("securetoken.googleapis.com", 456, 60, 1000);
    cache.forget("firestore.googleapis.com");
    assert(!cache.lookup("firestore.googleapis.com", 1001, address));
    assert(cache.lookup("securetoken.googleapis.com", 1001, address) && address == 456);
    cache.clear();
    assert(!cache.lookup("securetoken.googleapis.com", 1001, address));

    puts("Firebase DNS cache: TTL, wraparound, network invalidation and failure tests passed.");
}
