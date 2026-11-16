#include <Arduino.h>
#include <Wire.h>
#include <math.h>
#include <string.h>
#include "IdleAudio.h"
#include "driver/i2s_tdm.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "HWCDC.h"
extern HWCDC USBSerial;

namespace {
// Waveshare ESP32-S3-Touch-AMOLED-2.06. ES7210 control shares the existing
// touch/RTC I2C bus. Audio data is four-slot TDM on independent I2S pins.
constexpr uint8_t kEs7210 = 0x40;
constexpr uint32_t kSampleRate = 24000;
constexpr int kSamples = 256;
constexpr int kBands = 20;
constexpr int kSlots = 4;
constexpr int kMclk = 16, kBclk = 41, kWs = 45, kDin = 42;

portMUX_TYPE audioLock = portMUX_INITIALIZER_UNLOCKED;
uint8_t recentLevels[kBands] = {};
volatile bool audioReady = false;
volatile bool audioWanted = false;
volatile IdleAudioState audioState = IdleAudioState::Unavailable;
i2s_chan_handle_t rxChannel = nullptr;

bool writeCodec(uint8_t reg, uint8_t value) {
  Wire.beginTransmission(kEs7210);
  Wire.write(reg);
  Wire.write(value);
  return Wire.endTransmission() == 0;
}

// Minimal MIC1/MIC2 bring-up derived from ES7210 reference register order.
// No AXP2101 writes: leave the known-working watch power configuration alone.
bool configureMicrophones() {
  Wire.beginTransmission(kEs7210);
  if (Wire.endTransmission() != 0) return false;
  static constexpr uint8_t setup[][2] = {
    {0x00,0xFF}, {0x00,0x41}, {0x01,0x3F},
    {0x09,0x30}, {0x0A,0x30},
    {0x23,0x2A}, {0x22,0x0A}, {0x20,0x0A}, {0x21,0x2A},
    {0x08,0x00},          // ES7210 is slave; ESP32 generates I2S clocks
    {0x40,0x43}, {0x41,0x70}, {0x42,0x70},
    {0x07,0x20}, {0x02,0xC1},
    {0x4B,0x00}, {0x4C,0xFF},  // MIC1/MIC2 enabled, MIC3/MIC4 off
    {0x43,0x1B}, {0x44,0x1B}, // microphone gain
    {0x11,0x60}, {0x12,0x02}, // 16-bit TDM
    {0x15,0x00}, {0x14,0x00}, // unmute
    {0x01,0x34}, {0x06,0x00},
    {0x47,0x08}, {0x48,0x08},
    {0x00,0x71}, {0x00,0x41}
  };
  for (const auto& row : setup)
    if (!writeCodec(row[0], row[1])) return false;
  return true;
}

bool createI2S() {
  i2s_chan_config_t cfg = I2S_CHANNEL_DEFAULT_CONFIG(I2S_NUM_AUTO, I2S_ROLE_MASTER);
  cfg.dma_desc_num = 4;
  cfg.dma_frame_num = 256;
  if (i2s_new_channel(&cfg, nullptr, &rxChannel) != ESP_OK) return false;
  i2s_tdm_config_t tdm = {
    .clk_cfg = I2S_TDM_CLK_DEFAULT_CONFIG(kSampleRate),
    .slot_cfg = I2S_TDM_PHILIPS_SLOT_DEFAULT_CONFIG(
      I2S_DATA_BIT_WIDTH_16BIT, I2S_SLOT_MODE_STEREO,
      static_cast<i2s_tdm_slot_mask_t>(I2S_TDM_SLOT0 | I2S_TDM_SLOT1 |
                                       I2S_TDM_SLOT2 | I2S_TDM_SLOT3)),
    .gpio_cfg = {
      .mclk = static_cast<gpio_num_t>(kMclk),
      .bclk = static_cast<gpio_num_t>(kBclk),
      .ws   = static_cast<gpio_num_t>(kWs),
      .dout = I2S_GPIO_UNUSED,
      .din  = static_cast<gpio_num_t>(kDin),
      .invert_flags = { .mclk_inv = false, .bclk_inv = false, .ws_inv = false },
    },
  };
  // 24 kHz x 256 (6.144 MHz), with ES7210 clock doubler configured above.
  if (i2s_channel_init_tdm_mode(rxChannel, &tdm) != ESP_OK) {
    i2s_del_channel(rxChannel);
    rxChannel = nullptr;
    return false;
  }
  return true;
}

// Simple Goertzel bands from real MIC1 PCM. This is intentionally small and
// avoids FFT allocations, storage, playback, and changes to the BLE pipeline.
void analyze(const int16_t* pcm, uint8_t (&out)[kBands]) {
  float samples[kSamples];
  float average = 0.0f;
  for (int i = 0; i < kSamples; ++i) average += pcm[i];
  average /= kSamples;
  float peak = 0;
  for (int i = 0; i < kSamples; ++i) {
    const float raw = (static_cast<float>(pcm[i]) - average) / 32768.0f;
    // Hann window to reduce FFT-bin discontinuity artifacts.
    samples[i] = raw * (0.5f - 0.5f * cosf(6.28318530718f * i / (kSamples-1)));
    if (fabsf(raw) > peak) peak = fabsf(raw);
  }
  // Avoid displaying visual activity from a completely silent/uninitialized ADC.
  if (peak < 0.00018f) { memset(out, 0, sizeof(uint8_t)*kBands); return; }

  constexpr float bottom = 165.0f, top = 6400.0f;
  const float ratio = powf(top/bottom, 1.0f/(kBands-1));
  float freq = bottom;
  for (int band = 0; band < kBands; ++band) {
    const float omega = 6.28318530718f * freq / kSampleRate;
    const float coefficient = 2.0f * cosf(omega);
    float s1 = 0, s2 = 0;
    for (int i=0; i<kSamples; ++i) {
      const float s = samples[i] + coefficient*s1 - s2;
      s2 = s1; s1 = s;
    }
    const float power = fmaxf(0.0f, s1*s1 + s2*s2 - coefficient*s1*s2);
    const float amplitude = sqrtf(power)*2.0f/kSamples;
    // Fixed audio gain; never inject animation or fake microphone values.
    const float scaled = fminf(1.0f, sqrtf(fmaxf(0.0f, amplitude-0.00012f))*12.0f);
    out[band] = static_cast<uint8_t>(scaled*20.0f + 0.5f);
    freq *= ratio;
  }
}

void microphoneTask(void*) {
  // Four ES7210 TDM slots: MIC1, reference MIC3, MIC2, unused MIC4.
  static int16_t interleaved[kSamples*kSlots];
  static int16_t mono[kSamples];
  uint8_t oldLevels[kBands] = {};
  bool running = false;
  uint8_t consecutiveErrors = 0;
  uint8_t enableErrors = 0;
  while (true) {
    if (!audioWanted) {
      if (running) {
        i2s_channel_disable(rxChannel);
        running = false;
      }
      vTaskDelay(pdMS_TO_TICKS(50));
      continue;
    }
    if (!running) {
      if (i2s_channel_enable(rxChannel) != ESP_OK) {
        if (++enableErrors >= 3) {
          audioState = IdleAudioState::Fault;
          audioReady = false;
          audioWanted = false;
          USBSerial.println("IDLE AUDIO: I2S enable failed");
        }
        vTaskDelay(pdMS_TO_TICKS(250));
        continue;
      }
      enableErrors = 0;
      consecutiveErrors = 0;
      running = true;
    }
    size_t got = 0;
    const esp_err_t err = i2s_channel_read(rxChannel, interleaved,
                                            sizeof(interleaved), &got, 50);
    if (err != ESP_OK || got < sizeof(interleaved)) {
      if (++consecutiveErrors >= 50) {
        // Audio acquisition has stopped; never leave stale moving bars.
        audioState = IdleAudioState::Fault;
        audioReady = false;
        audioWanted = false;
        taskENTER_CRITICAL(&audioLock);
        memset(recentLevels, 0, sizeof(recentLevels));
        taskEXIT_CRITICAL(&audioLock);
        USBSerial.println("IDLE AUDIO: capture timed out");
      }
      vTaskDelay(pdMS_TO_TICKS(10));
      continue;
    }
    consecutiveErrors = 0;
    // G5A33: check all four ES7210 TDM slots. The onboard MIC1/MIC2
    // channels commonly occupy slots 0 and 2; the others are reference/unused.
    // Reading only slot 0 could report QUIET even when MIC2 is active.
    int slotPeak[kSlots] = {};
    for (int i = 0; i < kSamples; ++i) {
      for (int slot = 0; slot < kSlots; ++slot) {
        const int sample = static_cast<int>(interleaved[i * kSlots + slot]);
        const int magnitude = sample < 0 ? -sample : sample;
        if (magnitude > slotPeak[slot]) slotPeak[slot] = magnitude;
      }
    }
    const int micSlot = slotPeak[2] > slotPeak[0] ? 2 : 0;
    for (int i = 0; i < kSamples; ++i)
      mono[i] = interleaved[i * kSlots + micSlot];
    uint8_t levels[kBands];
    analyze(mono, levels);
    bool measuredActivity = false;
    for (int i=0; i<kBands; ++i)
      if (levels[i] != 0) { measuredActivity = true; break; }
    audioState = measuredActivity ? IdleAudioState::Active : IdleAudioState::Silent;
    // G5A38.4: removed periodic USB CDC printf (every 5 seconds).
    // On some ESP32-S3 builds it blocks the audio task when no USB host
    // drains the serial output. Keep errors logged only on actual failures.
    for (int i=0; i<kBands; ++i) {
      // Fast rise / gentle fall, with no autonomous animation.
      oldLevels[i] = levels[i] > oldLevels[i]
        ? levels[i] : static_cast<uint8_t>((2*oldLevels[i] + levels[i])/3);
    }
    taskENTER_CRITICAL(&audioLock);
    memcpy(recentLevels, oldLevels, sizeof(recentLevels));
    taskEXIT_CRITICAL(&audioLock);
    vTaskDelay(pdMS_TO_TICKS(65));
  }
}
} // namespace

void initializeIdleAudio() {
  // Call after initializeTouch(), which initializes Wire(15,14). Failure of
  // this optional subsystem must never prevent BLE, GPS, or HUD startup.
  if (!createI2S()) { USBSerial.println("IDLE AUDIO: I2S unavailable"); return; }
  // ES7210 requires MCLK to be present while its registers are initialized.
  if (i2s_channel_enable(rxChannel) != ESP_OK) {
    USBSerial.println("IDLE AUDIO: I2S clock start failed");
    i2s_del_channel(rxChannel); rxChannel = nullptr;
    return;
  }
  delay(15);
  const bool configured = configureMicrophones();
  i2s_channel_disable(rxChannel);
  if (!configured) {
    USBSerial.println("IDLE AUDIO: ES7210 unavailable");
    i2s_del_channel(rxChannel); rxChannel = nullptr;
    return;
  }
  BaseType_t ok = xTaskCreatePinnedToCore(microphoneTask, "idleMic", 8192,
                                         nullptr, 1, nullptr, 0);
  if (ok != pdPASS) {
    USBSerial.println("IDLE AUDIO: task unavailable");
    i2s_del_channel(rxChannel); rxChannel = nullptr;
    return;
  }
  audioState = IdleAudioState::Waiting;
  audioReady = true;
  USBSerial.println("IDLE AUDIO: ES7210 TDM ready");
}

void setIdleAudioActive(bool active) {
  const bool want = audioReady && active;
  if (want && !audioWanted) {
    // A new idle period starts without presenting stale levels from a prior one.
    taskENTER_CRITICAL(&audioLock);
    memset(recentLevels, 0, sizeof(recentLevels));
    taskEXIT_CRITICAL(&audioLock);
    audioState = IdleAudioState::Waiting;
  }
  audioWanted = want;
}

IdleAudioState getIdleAudioState() { return audioState; }

bool getIdleAudioLevels(uint8_t (&levels)[20]) {
  if (!audioReady) return false;
  taskENTER_CRITICAL(&audioLock);
  memcpy(levels, recentLevels, sizeof(recentLevels));
  taskEXIT_CRITICAL(&audioLock);
  return true;
}
