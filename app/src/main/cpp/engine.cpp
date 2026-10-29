#include "engine.h"
#include <algorithm>
#include <memory>
#include <mutex>
#include <stdexcept>

namespace mel {
namespace {
std::once_flag initialized;
void silentLog(ggml_log_level, const char *, void *) {} // Never put prompts/model metadata in logcat.
void require(bool condition, const char *message) { if (!condition) throw std::runtime_error(message); }
}
Engine::~Engine() { unload(); }
bool Engine::abort(void *data) { return static_cast<Engine *>(data)->cancelled.load(); }
bool Engine::progress(float, void *data) { return !abort(data); }
void Engine::check() { require(!cancelled.load(), "処理を停止しました。"); }
void Engine::unload() {
    if (context) { llama_free(context); context = nullptr; }
    if (model) { llama_model_free(model); model = nullptr; }
}
void Engine::load(const std::string &path, int contextSize, int threads) {
    check();
    std::call_once(initialized, [] { llama_log_set(silentLog, nullptr); llama_backend_init(); });
    unload();
    auto params = llama_model_default_params();
    params.n_gpu_layers = 0;
    params.load_mode = LLAMA_LOAD_MODE_MMAP;
    params.progress_callback = progress;
    params.progress_callback_user_data = this;
    model = llama_model_load_from_file(path.c_str(), params);
    check();
    require(model, "モデルを開けません。対応するテキスト用GGUFか、空きメモリを確認してください。");
    auto ctx = llama_context_default_params();
    ctx.n_ctx = std::min<int>(contextSize, llama_model_n_ctx_train(model));
    if (ctx.n_ctx < static_cast<uint32_t>(contextSize)) { unload(); throw std::runtime_error("4096トークン以上に対応するモデルを選んでください。"); }
    ctx.n_batch = 256;
    ctx.n_ubatch = 128;
    ctx.n_threads = std::clamp(threads, 1, 6);
    ctx.n_threads_batch = ctx.n_threads;
    ctx.no_perf = true;
    ctx.abort_callback = abort;
    ctx.abort_callback_data = this;
    context = llama_init_from_model(model, ctx);
    if (!context) { unload(); throw std::runtime_error("推論用メモリを確保できません。他のアプリを閉じてください。"); }
    check();
}
std::vector<llama_token> Engine::tokenize(const std::vector<Turn> &turns) {
    check();
    require(context && model, "設定からローカルモデルを取得・選択してください。");
    std::vector<llama_chat_message> messages;
    for (const auto &turn : turns) messages.push_back({turn.role.c_str(), turn.content.c_str()});
    const char *tmpl = llama_model_chat_template(model, nullptr);
    require(tmpl, "チャット形式のないGGUFです。Instructモデルを選んでください。");
    int needed = llama_chat_apply_template(tmpl, messages.data(), messages.size(), true, nullptr, 0);
    require(needed > 0 && needed < 20000000, "このモデルのチャット形式には対応していません。");
    std::vector<char> formatted(needed + 1);
    int size = llama_chat_apply_template(tmpl, messages.data(), messages.size(), true, formatted.data(), formatted.size());
    require(size > 0 && size < static_cast<int>(formatted.size()), "チャット形式の変換に失敗しました。");
    auto vocab = llama_model_get_vocab(model);
    int count = -llama_tokenize(vocab, formatted.data(), size, nullptr, 0, true, true);
    require(count > 0, "会話のトークン数を計算できません。");
    std::vector<llama_token> tokens(count);
    require(llama_tokenize(vocab, formatted.data(), size, tokens.data(), count, true, true) == count, "トークン変換に失敗しました。");
    check();
    return tokens;
}
int Engine::count(const std::vector<Turn> &turns) { return tokenize(turns).size(); }
std::string Engine::generate(const std::vector<Turn> &turns, float temperature, int maxTokens) {
    auto tokens = tokenize(turns);
    maxTokens = std::clamp(maxTokens, 1, 768);
    require(tokens.size() + maxTokens + 8 <= llama_n_ctx(context), "会話がモデルの上限を超えました。設定や記憶を短くしてください。");
    auto memory = llama_get_memory(context);
    require(memory, "このモデルのメモリ形式は未対応です。");
    llama_memory_clear(memory, true);
    struct Clear { llama_memory_t memory; ~Clear() { llama_memory_clear(memory, true); } } clear{memory};
    for (size_t offset = 0; offset < tokens.size(); offset += 256) {
        check();
        int count = std::min<size_t>(256, tokens.size() - offset);
        int result = llama_decode(context, llama_batch_get_one(tokens.data() + offset, count));
        check();
        require(result == 0, "会話の読み込みに失敗しました。空きメモリを確認してください。");
    }
    std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(
        llama_sampler_chain_init(llama_sampler_chain_default_params()), llama_sampler_free);
    auto vocab = llama_model_get_vocab(model);
    llama_sampler_chain_add(sampler.get(), llama_sampler_init_penalties(llama_vocab_n_tokens(vocab), 128, 1.08f, 0.0f, 0.0f));
    if (temperature <= 0.0f) llama_sampler_chain_add(sampler.get(), llama_sampler_init_greedy());
    else {
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_top_k(40));
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_top_p(0.95f, 1));
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_min_p(0.05f, 1));
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }
    std::string output;
    for (int i = 0; i < maxTokens; ++i) {
        check();
        llama_token token = llama_sampler_sample(sampler.get(), context, -1);
        if (llama_vocab_is_eog(vocab, token)) break;
        std::vector<char> piece(256);
        int size = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, false);
        if (size < 0) { piece.resize(-size); size = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, false); }
        if (size > 0) output.append(piece.data(), size);
        if (i + 1 < maxTokens) {
            require(llama_decode(context, llama_batch_get_one(&token, 1)) == 0 || cancelled.load(), "返信の生成に失敗しました。");
        }
    }
    check();
    require(!output.empty(), "モデルの応答が空です。別のモデルや設定で再試行してください。");
    return output;
}
}
