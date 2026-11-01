#include "engine.h"
#include "ggml-hexagon.h"
#include <iostream>

// This runs only on a Linux CI host without a Qualcomm driver. It verifies that
// the compiled NPU backend rejects that environment, rather than claiming an
// NPU exists or dereferencing a failed driver's device pointer.
int main() {
    if (!mel::Engine::npuIncluded()) return 1;
    if (ggml_backend_hexagon_reg() != nullptr) return 2;
    std::cout << "Compiled Hexagon backend correctly reports a missing host NPU driver.\n";
    return 0;
}
