#pragma once
// G5A44: security decisions are deliberately independent of NimBLE callbacks
// so we can test downgrade rejection on a host compiler.
inline bool bleSecureConnection(bool encrypted, bool authenticated, bool bonded) {
    return encrypted && authenticated && bonded;
}
