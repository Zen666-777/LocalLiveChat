#include "engine.h"
#include <iostream>
#include <stdexcept>

int main(int argc, char **argv) {
    if (argc != 2) return 2;
    try {
        if (mel::completeUtf8("a\xe3\x81") != "a" || mel::completeUtf8("a\xf0\x9f\x98") != "a" ||
            mel::completeUtf8("こんにちは🙂") != "こんにちは🙂") throw std::runtime_error("partial UTF-8");
        mel::Engine engine;
        engine.begin();
        engine.load(argv[1], 4096, 2);
        std::vector<mel::Turn> turns = {
            {"system", "あなたはメル。親しみやすい日本語の会話相手です。短く自然に返事してください。"},
            {"user", "ただいま。今日は少し疲れたよ。"},
            {"assistant", "おかえり。お疲れさま。ゆっくり聞かせて。"},
            {"user", "ありがとう。温かい飲み物がほしいな。"}
        };
        if (engine.count(turns) <= 0 || engine.count(turns) >= 4000) throw std::runtime_error("token count");
        int streamed = 0;
        const auto reply = engine.generate(turns, 0.0f, 64, [&](int stage, int current, int, const std::string &text) {
            if (stage == 2 && current > 0 && !text.empty()) ++streamed;
        });
        if (reply.empty()) throw std::runtime_error("empty inference");
        if (streamed == 0) throw std::runtime_error("stream not delivered");
        std::cout << "Japanese multi-turn generation: " << reply << "\n";
        auto summary = engine.generate({{"system", "次の会話を短く要約して。"}, {"user", "ユーザーは温かい飲み物を好む。メルに仕事の疲れを話した。"}}, 0.0f, 64);
        if (summary.empty()) throw std::runtime_error("empty summary");
        std::cout << "On-device memory prompt: " << summary << "\n";
        // Oversized input must be rejected, never silently truncated.
        bool rejected = false;
        try { engine.generate({{"user", std::string(200000, 'x')}}, 0.0f, 64); }
        catch (const std::runtime_error &) { rejected = true; }
        if (!rejected) throw std::runtime_error("oversized prompt was accepted");
        engine.cancel();
        rejected = false;
        try { engine.generate(turns, 0.0f, 64); } catch (const std::runtime_error &) { rejected = true; }
        if (!rejected) throw std::runtime_error("cancel not respected");
        engine.begin();
        if (engine.generate(turns, 0.0f, 8).empty()) throw std::runtime_error("retry failed");
        engine.unload();
        std::cout << "Native inference, memory, overflow, cancellation and retry passed.\n";
        return 0;
    } catch (const std::exception &e) { std::cerr << e.what() << "\n"; return 1; }
}
