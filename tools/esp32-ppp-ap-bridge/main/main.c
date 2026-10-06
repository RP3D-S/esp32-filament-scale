// ESP32 PPP-over-serial uplink -> WiFi SoftAP bridge with NAT.
//
//   Internet <- PC (pppd + NAT) <-USB/UART0-> ESP32 (PPP client + NAPT) <-WiFi AP-> clients
//
// Target: ESP-IDF v5.2+, classic ESP32 (e.g. Keyestudio ESP32 PLUS, WROOM-32E).

#include <string.h>
#include "esp_event.h"
#include "esp_log.h"
#include "esp_netif.h"
#include "esp_netif_ppp.h"
#include "esp_wifi.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/uart.h"
#include "nvs_flash.h"
#include "lwip/ip_addr.h"

static const char *TAG = "bridge";

#define PPP_UART      UART_NUM_0
#define PPP_RX_BUF    2048
#define PPP_RETRY_SEC 10

static esp_netif_t *s_ppp_netif;
static esp_netif_t *s_ap_netif;
static volatile bool s_ppp_up;

// ---- PPP over UART glue ---------------------------------------------------

typedef struct {
    esp_netif_driver_base_t base;
} ppp_uart_driver_t;

static esp_err_t ppp_transmit(void *h, void *buffer, size_t len)
{
    (void)h;
    return uart_write_bytes(PPP_UART, buffer, len) < 0 ? ESP_FAIL : ESP_OK;
}

static esp_err_t ppp_post_attach(esp_netif_t *netif, esp_netif_iodriver_handle h)
{
    ppp_uart_driver_t *drv = (ppp_uart_driver_t *)h;
    drv->base.netif = netif;
    const esp_netif_driver_ifconfig_t ifcfg = {
        .handle = drv,
        .transmit = ppp_transmit,
    };
    return esp_netif_set_driver_config(netif, &ifcfg);
}

static void uart_rx_task(void *arg)
{
    uint8_t *buf = malloc(PPP_RX_BUF);
    for (;;) {
        int n = uart_read_bytes(PPP_UART, buf, PPP_RX_BUF, pdMS_TO_TICKS(20));
        if (n > 0) {
            esp_netif_receive(s_ppp_netif, buf, n, NULL);
        }
    }
}

static void uart_init(void)
{
    const uart_config_t cfg = {
        .baud_rate = CONFIG_BRIDGE_PPP_BAUD,
        .data_bits = UART_DATA_8_BITS,
        .parity = UART_PARITY_DISABLE,
        .stop_bits = UART_STOP_BITS_1,
        .flow_ctrl = UART_HW_FLOWCTRL_DISABLE,
        .source_clk = UART_SCLK_DEFAULT,
    };
    ESP_ERROR_CHECK(uart_driver_install(PPP_UART, PPP_RX_BUF * 2, PPP_RX_BUF * 2, 0, NULL, 0));
    ESP_ERROR_CHECK(uart_param_config(PPP_UART, &cfg));
    ESP_ERROR_CHECK(uart_set_pin(PPP_UART, UART_PIN_NO_CHANGE, UART_PIN_NO_CHANGE,
                                 UART_PIN_NO_CHANGE, UART_PIN_NO_CHANGE));
}

// ---- Events ----------------------------------------------------------------

static void set_ap_dns(void)
{
    esp_netif_dns_info_t dns = { 0 };
    ip4_addr_t addr;
    ip4addr_aton(CONFIG_BRIDGE_DNS, &addr);
    dns.ip.type = ESP_IPADDR_TYPE_V4;
    dns.ip.u_addr.ip4.addr = addr.addr;

    dhcps_offer_t offer = OFFER_DNS;
    esp_netif_dhcps_stop(s_ap_netif);
    esp_netif_dhcps_option(s_ap_netif, ESP_NETIF_OP_SET, ESP_NETIF_DOMAIN_NAME_SERVER,
                           &offer, sizeof(offer));
    esp_netif_set_dns_info(s_ap_netif, ESP_NETIF_DNS_MAIN, &dns);
    esp_netif_dhcps_start(s_ap_netif);
}

static void on_ip_event(void *arg, esp_event_base_t base, int32_t id, void *data)
{
    if (id == IP_EVENT_PPP_GOT_IP) {
        const ip_event_got_ip_t *e = data;
        ESP_LOGI(TAG, "PPP up, local " IPSTR " peer " IPSTR,
                 IP2STR(&e->ip_info.ip), IP2STR(&e->ip_info.gw));

        esp_netif_set_default_netif(s_ppp_netif);

        // Use the PC's DNS for the ESP32 itself if pppd offered one, else fall back.
        esp_netif_dns_info_t d;
        if (esp_netif_get_dns_info(s_ppp_netif, ESP_NETIF_DNS_MAIN, &d) != ESP_OK ||
            d.ip.u_addr.ip4.addr == 0) {
            ip4_addr_t a;
            ip4addr_aton(CONFIG_BRIDGE_DNS, &a);
            d.ip.type = ESP_IPADDR_TYPE_V4;
            d.ip.u_addr.ip4.addr = a.addr;
            esp_netif_set_dns_info(s_ppp_netif, ESP_NETIF_DNS_MAIN, &d);
        }

        set_ap_dns();
        ESP_ERROR_CHECK(esp_netif_napt_enable(s_ap_netif));
        s_ppp_up = true;
        ESP_LOGI(TAG, "NAT enabled on AP, bridge ready");
    } else if (id == IP_EVENT_PPP_LOST_IP) {
        ESP_LOGW(TAG, "PPP lost IP");
        esp_netif_napt_disable(s_ap_netif);
        s_ppp_up = false;
    }
}

// lwIP gives up after a few LCP retries if pppd is not running yet, and never
// retries by itself. This supervisor restarts the link until it comes up.
static void ppp_supervisor_task(void *arg)
{
    for (;;) {
        vTaskDelay(pdMS_TO_TICKS(PPP_RETRY_SEC * 1000));
        if (!s_ppp_up) {
            ESP_LOGI(TAG, "PPP not up, restarting link");
            esp_netif_action_stop(s_ppp_netif, NULL, 0, NULL);
            vTaskDelay(pdMS_TO_TICKS(500));
            esp_netif_action_start(s_ppp_netif, NULL, 0, NULL);
        }
    }
}

// ---- Setup -----------------------------------------------------------------

static void wifi_ap_start(void)
{
    s_ap_netif = esp_netif_create_default_wifi_ap();
    wifi_init_config_t init = WIFI_INIT_CONFIG_DEFAULT();
    ESP_ERROR_CHECK(esp_wifi_init(&init));

    wifi_config_t ap = { 0 };
    strlcpy((char *)ap.ap.ssid, CONFIG_BRIDGE_AP_SSID, sizeof(ap.ap.ssid));
    ap.ap.ssid_len = strlen(CONFIG_BRIDGE_AP_SSID);
    ap.ap.channel = CONFIG_BRIDGE_AP_CHANNEL;
    ap.ap.max_connection = CONFIG_BRIDGE_AP_MAX_CLIENTS;
    if (strlen(CONFIG_BRIDGE_AP_PASSWORD) >= 8) {
        strlcpy((char *)ap.ap.password, CONFIG_BRIDGE_AP_PASSWORD, sizeof(ap.ap.password));
        ap.ap.authmode = WIFI_AUTH_WPA2_PSK;
    } else {
        ap.ap.authmode = WIFI_AUTH_OPEN;
    }
    ESP_ERROR_CHECK(esp_wifi_set_mode(WIFI_MODE_AP));
    ESP_ERROR_CHECK(esp_wifi_set_config(WIFI_IF_AP, &ap));
    ESP_ERROR_CHECK(esp_wifi_start());
    ESP_LOGI(TAG, "SoftAP '%s' on channel %d", CONFIG_BRIDGE_AP_SSID, CONFIG_BRIDGE_AP_CHANNEL);
}

static void ppp_start(void)
{
    esp_netif_config_t cfg = ESP_NETIF_DEFAULT_PPP();
    s_ppp_netif = esp_netif_new(&cfg);
    assert(s_ppp_netif);

    // pppd on the PC runs with "noauth", so no credentials are exchanged.
    esp_netif_ppp_config_t ppp_cfg = {
        .ppp_phase_event_enabled = false,
        .ppp_error_event_enabled = false,
    };
    ESP_ERROR_CHECK(esp_netif_ppp_set_params(s_ppp_netif, &ppp_cfg));

    static ppp_uart_driver_t drv = { .base = { .post_attach = ppp_post_attach } };
    ESP_ERROR_CHECK(esp_netif_attach(s_ppp_netif, &drv));

    ESP_ERROR_CHECK(esp_event_handler_register(IP_EVENT, ESP_EVENT_ANY_ID, on_ip_event, NULL));
    esp_netif_action_start(s_ppp_netif, NULL, 0, NULL);
}

void app_main(void)
{
    esp_err_t err = nvs_flash_init();
    if (err == ESP_ERR_NVS_NO_FREE_PAGES || err == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_ERROR_CHECK(nvs_flash_erase());
        ESP_ERROR_CHECK(nvs_flash_init());
    }
    ESP_ERROR_CHECK(esp_netif_init());
    ESP_ERROR_CHECK(esp_event_loop_create_default());

    uart_init();
    wifi_ap_start();
    ppp_start();

    xTaskCreate(uart_rx_task, "ppp_rx", 4096, NULL, 10, NULL);
    xTaskCreate(ppp_supervisor_task, "ppp_sup", 3072, NULL, 5, NULL);
}
