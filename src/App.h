#pragma once
#include "Renderer.h"
#include <chrono>

namespace mc {
class App {
public:
    int Run(HINSTANCE instance,int showCommand);
private:
    static LRESULT CALLBACK WindowProc(HWND window,UINT message,WPARAM wParam,LPARAM lParam);
    LRESULT HandleMessage(UINT message,WPARAM wParam,LPARAM lParam);
    bool CreateMainWindow(HINSTANCE instance,int showCommand);
    void SetMouseCaptured(bool captured);
    InputState PollInput() const;
    void FixedTick();
    void UpdateBreaking(const InputState& input,const std::optional<RayHit>& hit);

    HWND window_{};
    int clientWidth_{1280},clientHeight_{720};
    bool running_{true},mouseCaptured_{true},leftMouse_{false};
    long rawMouseX_{},rawMouseY_{};
    std::filesystem::path assetRoot_;
    WarningLog warnings_;
    BlockRegistry blocks_;
    TexturePack textures_;
    std::unique_ptr<World> world_;
    Player player_;
    Renderer renderer_;
    std::optional<RayHit> currentHit_;
    std::optional<Int3> breakingBlock_;
    int breakingTicks_{},destroyStage_{-1};
};
}
