// Used solely to make the debug APK declare arm64-v8a as its native ABI.
// Does not interact with vehicles, accounts, or networking.
__attribute__((visibility("default"))) int tailg_supported_abi(void) { return 64; }
