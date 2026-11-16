#pragma once
#include <stdint.h>

// Restores only fully checked snapshots; safe before the first showScreen().
// Returns true when a detailed 3x3 snapshot was restored.
bool restoreLastGoodSnapshots();

// Called ONLY after the 9-sector BEGIN/CELL/LVL/RAW/END batch validates.
// Failed/repeated/partial data never replaces the saved sample.
void persistLastGoodGrid();

// Called ONLY after the complete authenticated OPERATOR profile validates.
void persistLastGoodOperator(const char* username, const char* gang,
                             uint32_t wifi, uint32_t ble, uint32_t total,
                             uint32_t badges);
