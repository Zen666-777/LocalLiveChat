#include "engine.h"
#include <nlohmann/json.hpp>
#include <chrono>
#include <fstream>
#include <iostream>
#include <stdexcept>

// Only public synthetic fixtures are used in CI. This is a baseline generator,
// not a grader and not evidence of parity with another product.
int main(int argc, char **argv) {
    if (argc != 4) { std::cerr << "usage: mel-eval model.gguf requests.jsonl responses.jsonl\n"; return 2; }
    using Clock = std::chrono::steady_clock;
    using json = nlohmann::ordered_json;
    auto seconds = [](auto duration) { return std::chrono::duration<double>(duration).count(); };
    try {
        std::ifstream requests(argv[2]);
        std::ofstream responses(argv[3]);
        if (!requests || !responses) throw std::runtime_error("cannot open evaluation files");
        mel::Engine engine;
        engine.begin();
        auto loading = Clock::now();
        engine.load(argv[1], 4096, 2);
        const double loadSeconds = seconds(Clock::now() - loading);
        int count = 0;
        std::string line;
        while (std::getline(requests, line)) {
            if (line.empty()) continue;
            auto request = json::parse(line);
            std::vector<mel::Turn> turns;
            std::string system;
            // Match LocalChatService.mergeSystem without flattening dialogue.
            bool leading = true;
            for (const auto &item : request.at("messages")) {
                std::string role = item.at("role"), content = item.at("content");
                if (leading && role == "system") { if (!system.empty()) system += "\n\n"; system += content; }
                else {
                    if (leading) { if (!system.empty()) turns.push_back({"system", system}); leading = false; }
                    turns.push_back({role, content});
                }
            }
            if (leading && !system.empty()) turns.push_back({"system", system});
            const int inputTokens = engine.count(turns);
            const auto start = Clock::now();
            double firstToken = -1;
            int outputTokens = 0;
            auto text = engine.generate(turns, request.value("temperature", 0.7f), request.value("max_tokens", 256),
                [&](int stage, int current, int, const std::string &partial) {
                    if (stage == 2 && current > 0) {
                        outputTokens = current;
                        if (firstToken < 0 && !partial.empty()) firstToken = seconds(Clock::now() - start);
                    }
                }, request.value("seed", 42u));
            const double elapsed = seconds(Clock::now() - start);
            responses << json{{"id", request.at("id")}, {"text", text}, {"input_tokens", inputTokens},
                {"output_tokens", outputTokens}, {"elapsed_seconds", elapsed}, {"first_text_seconds", firstToken},
                {"model_load_seconds", loadSeconds}, {"seed", request.value("seed", 42u)}}.dump() << '\n';
            responses.flush();
            std::cout << request.at("id").get<std::string>() << ": generated " << outputTokens << " tokens\n";
            ++count;
        }
        if (count == 0) throw std::runtime_error("no evaluation cases");
        return 0;
    } catch (const std::exception &failure) { std::cerr << failure.what() << '\n'; return 1; }
}
