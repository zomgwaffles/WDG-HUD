#pragma once
#include <stdint.h>

// G5A31: Mic-driven idle spectrum. No network, BLE or display access from audio task.
// Initialization runs once after Wire.begin() and before BLE initialization.
void initializeIdleAudio();
// Capture is enabled only while the 3x3 status header is idle.
void setIdleAudioActive(bool active);
// Returns false if the onboard ES7210/I2S path cannot be initialized.
// Levels are measured from microphone samples, never synthesized.
bool getIdleAudioLevels(uint8_t (&levels)[20]);

// Capture state distinguishes no microphone, startup, silence and real audio.
// The HUD never fabricates spectrum levels while the microphone is silent.
enum class IdleAudioState : uint8_t {
  Unavailable,  // ES7210/I2S could not initialize
  Waiting,      // initialized; waiting for the first complete audio frame
  Silent,       // real frames received, but no measured spectrum energy
  Active,       // real microphone samples have nonzero spectrum energy
  Fault         // I2S capture failed after initialization
};
IdleAudioState getIdleAudioState();
