#pragma once
#include "Player.h"
#include <Windows.h>
#include <d3d11.h>
#include <DirectXMath.h>
#include <wrl/client.h>

namespace mc {
class Renderer {
public:
    bool Initialize(HWND window,int width,int height,const TexturePack& textures,const std::filesystem::path& assetRoot,WarningLog& warnings);
    bool InitializeFirstPersonArm(const std::filesystem::path& assetRoot,WarningLog& warnings);
    void Resize(int width,int height);
    void UploadChunk(ChunkCoord coord,const MeshData& mesh);
    void RemoveChunk(ChunkCoord coord);
    void SetFirstPersonSwing(float progress,bool swinging){firstPersonSwingProgress_=std::clamp(progress,0.0f,1.0f);firstPersonSwinging_=swinging;}
    void Render(const Player& player,const std::optional<RayHit>& target,int destroyStage,double interpolationAlpha,uint64_t worldTime);
    const std::wstring& LastError() const noexcept{return lastError_;}
private:
    struct GpuChunk {Microsoft::WRL::ComPtr<ID3D11Buffer> vb,ib;uint32_t indexCount{};uint64_t revision{};};
    struct FrameConstants {
        DirectX::XMFLOAT4X4 viewProjection{};
        float skyDarken{};
        float daylight{1.0f};
        float padding[2]{};
    };
    struct ArmConstants {DirectX::XMFLOAT4X4 modelProjection{};};
    struct ColorVertex {float x,y,z;uint32_t color;};
    struct SpriteVertex {float x,y,z,u,v;uint32_t color;};
    struct UiVertex {float x,y,u,v;};

    bool CreateDevice(HWND window);
    bool CreateTargets(int width,int height);
    bool CreateShaders();
    bool CreateTextureArray(const TexturePack& textures,WarningLog& warnings);
    bool CreateStandaloneTextures(const std::filesystem::path& assetRoot,WarningLog& warnings);
    bool CreateStates();
    bool Fail(const wchar_t* stage,HRESULT hr,const std::wstring& detail={});
    void DrawEnvironment(const Player& player,double interpolationAlpha,uint64_t worldTime);
    void DrawFirstPersonArm();
    void DrawCrosshair();
    void DrawCracks(const RayHit& hit,uint16_t textureSlice);
    void DrawOutline(const RayHit& hit);
    void UpdateFrameConstants(const Player& player,double interpolationAlpha,uint64_t worldTime);

    int width_{1},height_{1};
    Microsoft::WRL::ComPtr<ID3D11Device> device_;
    Microsoft::WRL::ComPtr<ID3D11DeviceContext> context_;
    Microsoft::WRL::ComPtr<IDXGISwapChain> swapChain_;
    Microsoft::WRL::ComPtr<ID3D11RenderTargetView> rtv_;
    Microsoft::WRL::ComPtr<ID3D11Texture2D> depth_;
    Microsoft::WRL::ComPtr<ID3D11DepthStencilView> dsv_;
    Microsoft::WRL::ComPtr<ID3D11VertexShader> voxelVs_,colorVs_,spriteVs_,uiVs_,armVs_;
    Microsoft::WRL::ComPtr<ID3D11PixelShader> voxelPs_,colorPs_,spritePs_,uiPs_;
    Microsoft::WRL::ComPtr<ID3D11InputLayout> voxelLayout_,colorLayout_,spriteLayout_,uiLayout_;
    Microsoft::WRL::ComPtr<ID3D11Buffer> frameCb_,armCb_,overlayVb_,overlayIb_,lineVb_,environmentVb_,uiVb_,armVb_;
    Microsoft::WRL::ComPtr<ID3D11ShaderResourceView> textureArray_,sunTexture_,cloudsTexture_,iconsTexture_,skinTexture_;
    Microsoft::WRL::ComPtr<ID3D11SamplerState> sampler_;
    Microsoft::WRL::ComPtr<ID3D11RasterizerState> solidRaster_,overlayRaster_,lineRaster_;
    Microsoft::WRL::ComPtr<ID3D11BlendState> alphaBlend_,invertBlend_;
    Microsoft::WRL::ComPtr<ID3D11DepthStencilState> depthState_,skyDepthState_;
    std::unordered_map<ChunkCoord,GpuChunk,ChunkCoordHash> chunks_;
    std::array<uint16_t,10> destroyStages_{};
    uint32_t iconsWidth_{},iconsHeight_{},skinWidth_{},skinHeight_{};
    float daylight_{1.0f},skyDarken_{};
    float firstPersonSwingProgress_{};
    bool firstPersonSwinging_{};
    DirectX::XMFLOAT4X4 currentViewProjection_{};
    std::wstring lastError_;
};
}
