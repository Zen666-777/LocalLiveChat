#pragma once
#include "llama.h"
#include <atomic>
#include <string>
#include <vector>

namespace mel {
struct Turn { std::string role, content; };
// All methods except cancel() run on the one inference worker.
class Engine {
public:
    ~Engine();
    void begin() { cancelled.store(false); }
    void cancel() { cancelled.store(true); }
    void load(const std::string &path, int contextSize, int threads);
    void unload();
    int count(const std::vector<Turn> &turns);
    std::string generate(const std::vector<Turn> &turns, float temperature, int maxTokens);
private:
    std::atomic<bool> cancelled{false};
    llama_model *model = nullptr;
    llama_context *context = nullptr;
    void check();
    std::vector<llama_token> tokenize(const std::vector<Turn> &turns);
    static bool abort(void *data);
    static bool progress(float, void *data);
};
}
