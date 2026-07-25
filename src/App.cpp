#include "App.h"
#include <shellapi.h>

namespace mc {
int App::Run(HINSTANCE instance,int showCommand){
    const HRESULT comResult=CoInitializeEx(nullptr,COINIT_MULTITHREADED);
    if(FAILED(comResult)&&comResult!=RPC_E_CHANGED_MODE){MessageBoxW(nullptr,L"COM initialization failed.",L"Minecraft2",MB_OK|MB_ICONERROR);return 1;}

    assetRoot_=FindAssetRoot();
    blocks_.Discover(assetRoot_,warnings_);
    textures_.Build(assetRoot_,blocks_.RequiredTextureNames(),warnings_);
    blocks_.ResolveTextures(textures_);
    world_=std::make_unique<World>(blocks_,assetRoot_,warnings_);
    world_->LoadLayerProfile(assetRoot_/L"worldgen"/L"flat_overworld.layers",warnings_);
    world_->UpdateStreaming(player_.Position());

    if(!CreateMainWindow(instance,showCommand)){if(SUCCEEDED(comResult))CoUninitialize();return 1;}
    if(!renderer_.Initialize(window_,clientWidth_,clientHeight_,textures_,assetRoot_,warnings_)){
        std::wstring message=L"Direct3D 11 initialization failed.\n\n";
        message+=renderer_.LastError();
        MessageBoxW(window_,message.c_str(),L"Minecraft2",MB_OK|MB_ICONERROR);
        if(SUCCEEDED(comResult))CoUninitialize();
        return 1;
    }
    if(!warnings_.Empty())MessageBoxW(window_,warnings_.Format().c_str(),L"Minecraft2 - asset warnings",MB_OK|MB_ICONWARNING);
    for(auto& update:world_->BuildDirtyMeshes(256))renderer_.UploadChunk(update.first,update.second);
    SetMouseCaptured(true);

    using clock=std::chrono::steady_clock;
    auto previous=clock::now();
    double accumulator=0.0;
    MSG message{};
    while(running_){
        while(PeekMessageW(&message,nullptr,0,0,PM_REMOVE)){
            if(message.message==WM_QUIT){running_=false;break;}
            TranslateMessage(&message);
            DispatchMessageW(&message);
        }
        if(!running_)break;

        const auto now=clock::now();
        const double frameSeconds=std::chrono::duration<double>(now-previous).count();
        previous=now;
        accumulator+=std::min(frameSeconds,0.25);

        const long mouseX=std::exchange(rawMouseX_,0),mouseY=std::exchange(rawMouseY_,0);
        if(mouseCaptured_)player_.ApplyMouseDelta(double(mouseX),double(mouseY));

        int ticks=0;
        while(accumulator>=kTickSeconds&&ticks<10){FixedTick();accumulator-=kTickSeconds;++ticks;}
        for(const auto coord:world_->TakeUnloaded())renderer_.RemoveChunk(coord);
        for(auto& update:world_->BuildDirtyMeshes(3))renderer_.UploadChunk(update.first,update.second);
        currentHit_=world_->Raycast(player_.EyePosition(),player_.LookDirection(),kReach);
        const int visibleStage=(currentHit_&&breakingBlock_&&*breakingBlock_==currentHit_->block)?destroyStage_:-1;
        const double interpolationAlpha=std::clamp(accumulator/kTickSeconds,0.0,1.0);
        renderer_.Render(player_,currentHit_,visibleStage,interpolationAlpha,world_->GameTime());
    }

    SetMouseCaptured(false);
    if(SUCCEEDED(comResult))CoUninitialize();
    return static_cast<int>(message.wParam);
}

bool App::CreateMainWindow(HINSTANCE instance,int showCommand){
    WNDCLASSEXW windowClass{sizeof(windowClass)};
    windowClass.style=CS_HREDRAW|CS_VREDRAW|CS_OWNDC;
    windowClass.lpfnWndProc=WindowProc;
    windowClass.hInstance=instance;
    windowClass.hCursor=LoadCursor(nullptr,IDC_ARROW);
    windowClass.lpszClassName=L"Minecraft2Window";
    if(!RegisterClassExW(&windowClass))return false;
    RECT rectangle{0,0,clientWidth_,clientHeight_};
    AdjustWindowRectEx(&rectangle,WS_OVERLAPPEDWINDOW,FALSE,0);
    window_=CreateWindowExW(0,windowClass.lpszClassName,L"Minecraft2 - Direct3D 11",WS_OVERLAPPEDWINDOW,CW_USEDEFAULT,CW_USEDEFAULT,rectangle.right-rectangle.left,rectangle.bottom-rectangle.top,nullptr,nullptr,instance,this);
    if(!window_)return false;
    RAWINPUTDEVICE rawInput{0x01,0x02,RIDEV_INPUTSINK,window_};
    RegisterRawInputDevices(&rawInput,1,sizeof(rawInput));
    ShowWindow(window_,showCommand);
    UpdateWindow(window_);
    return true;
}

LRESULT CALLBACK App::WindowProc(HWND window,UINT message,WPARAM wParam,LPARAM lParam){
    App* app=reinterpret_cast<App*>(GetWindowLongPtrW(window,GWLP_USERDATA));
    if(message==WM_NCCREATE){
        const auto creation=reinterpret_cast<CREATESTRUCTW*>(lParam);
        app=static_cast<App*>(creation->lpCreateParams);
        app->window_=window;
        SetWindowLongPtrW(window,GWLP_USERDATA,reinterpret_cast<LONG_PTR>(app));
    }
    return app?app->HandleMessage(message,wParam,lParam):DefWindowProcW(window,message,wParam,lParam);
}

LRESULT App::HandleMessage(UINT message,WPARAM wParam,LPARAM lParam){
    switch(message){
    case WM_DESTROY:running_=false;PostQuitMessage(0);return 0;
    case WM_SIZE:clientWidth_=LOWORD(lParam);clientHeight_=HIWORD(lParam);if(wParam!=SIZE_MINIMIZED)renderer_.Resize(clientWidth_,clientHeight_);return 0;
    case WM_INPUT:{
        UINT size=0;
        GetRawInputData(reinterpret_cast<HRAWINPUT>(lParam),RID_INPUT,nullptr,&size,sizeof(RAWINPUTHEADER));
        std::vector<std::byte> data(size);
        if(GetRawInputData(reinterpret_cast<HRAWINPUT>(lParam),RID_INPUT,data.data(),&size,sizeof(RAWINPUTHEADER))==size){
            const auto* raw=reinterpret_cast<RAWINPUT*>(data.data());
            if(raw->header.dwType==RIM_TYPEMOUSE){rawMouseX_+=raw->data.mouse.lLastX;rawMouseY_+=raw->data.mouse.lLastY;}
        }
        return 0;
    }
    case WM_LBUTTONDOWN:leftMouse_=true;if(!mouseCaptured_)SetMouseCaptured(true);return 0;
    case WM_LBUTTONUP:leftMouse_=false;return 0;
    case WM_KEYDOWN:if(wParam==VK_ESCAPE&&!((lParam>>30)&1)){SetMouseCaptured(!mouseCaptured_);return 0;}break;
    case WM_ACTIVATEAPP:if(!wParam)SetMouseCaptured(false);return 0;
    }
    return DefWindowProcW(window_,message,wParam,lParam);
}

void App::SetMouseCaptured(bool captured){
    mouseCaptured_=captured;
    leftMouse_=false;
    breakingBlock_.reset();
    breakingTicks_=0;
    destroyStage_=-1;
    if(!window_)return;
    if(captured){
        RECT rectangle{};GetClientRect(window_,&rectangle);
        POINT topLeft{rectangle.left,rectangle.top},bottomRight{rectangle.right,rectangle.bottom};
        ClientToScreen(window_,&topLeft);ClientToScreen(window_,&bottomRight);
        const RECT screen{topLeft.x,topLeft.y,bottomRight.x,bottomRight.y};
        ClipCursor(&screen);
        while(ShowCursor(FALSE)>=0){}
        SetFocus(window_);
    }else{
        ClipCursor(nullptr);
        while(ShowCursor(TRUE)<0){}
    }
}

InputState App::PollInput() const{
    const auto down=[](int key){return (GetAsyncKeyState(key)&0x8000)!=0;};
    InputState input;
    if(!mouseCaptured_)return input;
    input.forward=down('W');input.back=down('S');input.left=down('A');input.right=down('D');input.jump=down(VK_SPACE);input.sneak=down(VK_LSHIFT)||down(VK_RSHIFT);input.sprint=down(VK_LCONTROL)||down(VK_RCONTROL);input.breakBlock=leftMouse_;
    return input;
}

void App::FixedTick(){
    const InputState input=PollInput();
    player_.Tick(input,*world_);
    world_->Tick();
    world_->UpdateStreaming(player_.Position());
    const auto hit=world_->Raycast(player_.EyePosition(),player_.LookDirection(),kReach);
    UpdateBreaking(input,hit);
    currentHit_=hit;
}

void App::UpdateBreaking(const InputState& input,const std::optional<RayHit>& hit){
    if(!input.breakBlock||!hit){breakingBlock_.reset();breakingTicks_=0;destroyStage_=-1;return;}
    const auto& definition=blocks_.Get(hit->id);
    if(!definition.breakable||definition.breakTicks<=0){breakingBlock_.reset();breakingTicks_=0;destroyStage_=-1;return;}
    if(!breakingBlock_||!(*breakingBlock_==hit->block)){breakingBlock_=hit->block;breakingTicks_=0;}
    ++breakingTicks_;
    if(breakingTicks_>=definition.breakTicks){
        world_->SetBlock(hit->block.x,hit->block.y,hit->block.z,0);
        breakingBlock_.reset();breakingTicks_=0;destroyStage_=-1;
        return;
    }
    destroyStage_=std::clamp((breakingTicks_*10)/definition.breakTicks,0,9);
}
}