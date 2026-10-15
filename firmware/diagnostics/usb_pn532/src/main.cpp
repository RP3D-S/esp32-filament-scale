#include <Arduino.h>
#include <esp_intr_alloc.h>
#include <usb/usb_host.h>

static usb_host_client_handle_t client = nullptr;
static usb_device_handle_t device = nullptr;
static uint8_t pendingAddress = 0;
static bool disconnected = false;
static bool hostReady = false;

static void clientEvent(const usb_host_client_event_msg_t *event, void *) {
    if (event->event == USB_HOST_CLIENT_EVENT_NEW_DEV) {
        pendingAddress = event->new_dev.address;
    } else if (event->event == USB_HOST_CLIENT_EVENT_DEV_GONE) {
        disconnected = true;
    }
}

static void inspectDevice(uint8_t address) {
    esp_err_t error = usb_host_device_open(client, address, &device);
    Serial.printf("[USB] open address=%u result=%s\n", address, esp_err_to_name(error));
    if (error != ESP_OK) return;

    const usb_device_desc_t *desc = nullptr;
    if (usb_host_get_device_descriptor(device, &desc) == ESP_OK) {
        Serial.printf("[USB] DEVICE vid=%04X pid=%04X class=%02X configurations=%u\n",
                      desc->idVendor, desc->idProduct, desc->bDeviceClass, desc->bNumConfigurations);
    }
    const usb_config_desc_t *config = nullptr;
    if (usb_host_get_active_config_descriptor(device, &config) != ESP_OK) return;
    const uint8_t *raw = reinterpret_cast<const uint8_t *>(config);
    for (size_t offset = 0; offset + 2 <= config->wTotalLength;) {
        const uint8_t length = raw[offset];
        if (length < 2 || offset + length > config->wTotalLength) break;
        const uint8_t type = raw[offset + 1];
        if (type == USB_B_DESCRIPTOR_TYPE_INTERFACE && length >= 9) {
            Serial.printf("[USB] interface=%u alt=%u endpoints=%u class=%02X subclass=%02X protocol=%02X\n",
                          raw[offset + 2], raw[offset + 3], raw[offset + 4],
                          raw[offset + 5], raw[offset + 6], raw[offset + 7]);
        } else if (type == USB_B_DESCRIPTOR_TYPE_ENDPOINT && length >= 7) {
            Serial.printf("[USB] endpoint=%02X attributes=%02X maxPacket=%u\n",
                          raw[offset + 2], raw[offset + 3],
                          raw[offset + 4] | (raw[offset + 5] << 8));
        }
        offset += length;
    }
}

void setup() {
    Serial.begin(115200);
    delay(500);
    Serial.println("\n[USB] ESP32-S3 USB PN532 diagnostic - enumeration stage");
    Serial.println("[USB] Native port: GPIO19 D-, GPIO20 D+; console: UART0");
    usb_host_config_t config = {};
    config.intr_flags = ESP_INTR_FLAG_LEVEL1;
    esp_err_t error = usb_host_install(&config);
    Serial.printf("[USB] host install=%s\n", esp_err_to_name(error));
    if (error != ESP_OK) return;

    usb_host_client_config_t clientConfig = {};
    clientConfig.max_num_event_msg = 5;
    clientConfig.async.client_event_callback = clientEvent;
    error = usb_host_client_register(&clientConfig, &client);
    Serial.printf("[USB] client register=%s\n", esp_err_to_name(error));
    hostReady = error == ESP_OK;
}

void loop() {
    static uint32_t lastStatus = 0;
    if (hostReady) {
        uint32_t flags = 0;
        usb_host_lib_handle_events(0, &flags);
        usb_host_client_handle_events(client, 0);
        if (disconnected && device) {
            disconnected = false;
            usb_host_device_close(client, device);
            device = nullptr;
            Serial.println("[USB] device disconnected");
        }
        if (pendingAddress) {
            const uint8_t address = pendingAddress;
            pendingAddress = 0;
            inspectDevice(address);
        }
    }
    if (millis() - lastStatus >= 3000) {
        lastStatus = millis();
        Serial.printf("[USB] uptime=%us host=%s device=%s heap=%u\n",
                      (unsigned)(millis() / 1000), hostReady ? "OK" : "ERROR",
                      device ? "PRESENT" : "NONE", (unsigned)ESP.getFreeHeap());
    }
    delay(1);
}
