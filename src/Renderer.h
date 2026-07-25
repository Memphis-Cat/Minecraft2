#pragma once
#include "Player.h"
#include <Windows.h>
#include <d3d11.h>
#include <DirectXMath.h>
#include <wrl/client.h>

namespace mc {
class Renderer {
public:
    bool Initialize(HWND window,int width,int height,const TexturePack& textures,WarningLog& warnings);
    void Resize(int width,int height);
    void UploadChunk(ChunkCoord coord,const MeshData& mesh);
    void RemoveChunk(ChunkCoord coord);
    void Render(const Player& player,const std::optional<RayHit>& target,int destroyStage);
private:
    struct GpuChunk { Microsoft::WRL::ComPtr<ID3D11Buffer> vb,ib; uint32_t indexCount{}; uint64_t revision{}; };
    struct FrameConstants { DirectX::XMFLOAT4X4 viewProjection{}; };
    struct ColorVertex { float x,y,z; uint32_t color; };
    struct Plane { float a,b,c,d; };
    bool CreateDevice(HWND window);
    bool CreateTargets(int width,int height);
    bool CreateShaders();
    bool CreateTextureArray(const TexturePack& textures,WarningLog& warnings);
    bool CreateStates();
    std::array<Plane,6> ExtractFrustum(const DirectX::XMFLOAT4X4& m) const;
    bool Visible(ChunkCoord coord,const std::array<Plane,6>& planes) const;
    void DrawCracks(const RayHit& hit,uint16_t textureSlice);
    void DrawOutline(const RayHit& hit);
    void UpdateFrameConstants(const Player& player);

    int width_{1},height_{1};
    Microsoft::WRL::ComPtr<ID3D11Device> device_;
    Microsoft::WRL::ComPtr<ID3D11DeviceContext> context_;
    Microsoft::WRL::ComPtr<IDXGISwapChain> swapChain_;
    Microsoft::WRL::ComPtr<ID3D11RenderTargetView> rtv_;
    Microsoft::WRL::ComPtr<ID3D11Texture2D> depth_;
    Microsoft::WRL::ComPtr<ID3D11DepthStencilView> dsv_;
    Microsoft::WRL::ComPtr<ID3D11VertexShader> voxelVs_,colorVs_;
    Microsoft::WRL::ComPtr<ID3D11PixelShader> voxelPs_,colorPs_;
    Microsoft::WRL::ComPtr<ID3D11InputLayout> voxelLayout_,colorLayout_;
    Microsoft::WRL::ComPtr<ID3D11Buffer> frameCb_,overlayVb_,overlayIb_,lineVb_;
    Microsoft::WRL::ComPtr<ID3D11ShaderResourceView> textureArray_;
    Microsoft::WRL::ComPtr<ID11SymplerState> sampler_;
    Microsoft::WRL::ComPtr<ID3D11RasterizerState> solidRaster_,overlayRaster_,lineRaster_;
    Microsoft::WRL::ComPtr<ID3D11BlendState> alphaBlend_;
    Microsoft::WRL::ComPtr<ID3D11DepthStencilState> depthState_;
    std::unordered_map<ChunkCoord,GpuChunk,ChunkCoordHash> chunks_;
    std::array<uint16_t,10> destroyStages_{};
    DirectX::XMFLOAT4X4 currentViewProjection_{};
};
}
