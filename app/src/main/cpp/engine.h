#pragma once
#include "llama.h"
#include <atomic>
#include <string>
#include <vector>
#include <functional>

namespace mel {
struct Turn { std::string role, content; };
using Progress = std::function<void(int, int, int, const std::string &)>;
std::string completeUtf8(const std::string &text);
// All methods except cancel() run on the one inference worker.
class Engine {
public:
    ~Engine();
    void begin() { cancelled.store(false); }
    void cancel() { cancelled.store(true); }
    void load(const std::string &path, int contextSize, int threads, const Progress &report = {});
    void unload();
    int count(const std::vector<Turn> &turns);
    std::string generate(const std::vector<Turn> &turns, float temperature, int maxTokens, const Progress &report = {}, uint32_t seed = LLAMA_DEFAULT_SEED);
private:
    std::atomic<bool> cancelled{false};
    llama_model *model = nullptr;
    llama_context *context = nullptr;
    Progress loadingProgress;
    int loadingPercent = -1;
    void check();
    std::vector<llama_token> tokenize(const std::vector<Turn> &turns);
    static bool abort(void *data);
    static bool progress(float, void *data);
};
}
