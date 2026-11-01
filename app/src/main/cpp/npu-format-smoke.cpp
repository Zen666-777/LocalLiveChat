#include "engine.h"
#include "gguf.h"
#include <cstdio>
#include <memory>
#include <stdexcept>
#include <iostream>

int main(int argc, char **argv) {
    if (argc != 2) return 2;
    const std::string path = argv[1];
    try {
        for (auto type : {GGML_TYPE_Q4_0, GGML_TYPE_Q8_0, GGML_TYPE_Q4_K, GGML_TYPE_Q6_K}) {
            std::unique_ptr<ggml_context, decltype(&ggml_free)> tensors(ggml_init({65536, nullptr, false}), ggml_free);
            if (!tensors) throw std::runtime_error("test allocation failed");
            auto weight = ggml_new_tensor_2d(tensors.get(), type, 256, 32);
            ggml_set_name(weight, "test.weight");
            std::unique_ptr<gguf_context, decltype(&gguf_free)> file(gguf_init_empty(), gguf_free);
            gguf_add_tensor(file.get(), weight);
            if (!gguf_write_to_file(file.get(), path.c_str(), false)) throw std::runtime_error("test GGUF write failed");
            bool expected = type == GGML_TYPE_Q4_0 || type == GGML_TYPE_Q8_0;
            if (mel::npuFormatSupported(path) != expected) throw std::runtime_error("NPU quantization gate failed");
        }
        std::remove(path.c_str());
        if (mel::npuFormatSupported(path)) throw std::runtime_error("missing file accepted");
        std::cout << "NPU Q4_0/Q8_0 acceptance and K-quant rejection passed (no physical NPU tested).\n";
        return 0;
    } catch (const std::exception &e) {
        std::remove(path.c_str());
        std::cerr << e.what() << "\n";
        return 1;
    }
}
