#include "Renderer.h"
#include <d3dcompiler.h>
#include <wincodec.h>
#include <wrl/client.h>
#include <array>
#include <cstring>
#include <numbers>

using Microsoft::WRL::ComPtr;
using namespace DirectX;

namespace mc {
namespace {
constexpr char kArmShader[] = R"(
cbuffer ArmFrame : register(b1) { row_major float4x4 modelProjection; };
struct VSIn { float3 position:POSITION; float2 uv:TEXCOORD0; float4 color:COLOR0; };
struct PSIn { float4 position:SV_POSITION; float2 uv:TEXCOORD0; float4 color:COLOR0; };
PSIn ArmVS(VSIn input){ PSIn output; output.position=mul(float4(input.position,1),modelProjection); output.uv=input.uv; output.color=input.color; return output; }
)";

struct DecodedSkin {UINT width{},height{};std::vector<uint8_t> pixels;};
struct UvRect {float u0{},v0{},u1{},v1{};};

std::wstring AbsoluteForMessage(const std::filesystem::path& path){
    std::error_code error;
    const auto absolute=std::filesystem::absolute(path,error);
    return (error?path:absolute.lexically_normal()).wstring();
}

bool DecodeSkin(IWICImagingFactory* factory,const std::filesystem::path& path,DecodedSkin& image){
    image={};
    ComPtr<IWICBitmapDecoder> decoder;
    if(FAILED(factory->CreateDecoderFromFilename(path.c_str(),nullptr,GENERIC_READ,WICDecodeMetadataCacheOnLoad,decoder.GetAddressOf())))return false;
    ComPtr<IWICBitmapFrameDecode> frame;
    if(FAILED(decoder->GetFrame(0,frame.GetAddressOf())))return false;
    if(FAILED(frame->GetSize(&image.width,&image.height))||image.width==0||image.height==0)return false;
    ComPtr<IWICFormatConverter> converter;
    if(FAILED(factory->CreateFormatConverter(converter.GetAddressOf())))return false;
    if(FAILED(converter->Initialize(frame.Get(),GUID_WICPixelFormat32bppRGBA,WICBitmapDitherTypeNone,nullptr,0.0,WICBitmapPaletteTypeCustom)))return false;
    const size_t byteCount=static_cast<size_t>(image.width)*image.height*4;
    if(byteCount>std::numeric_limits<UINT>::max())return false;
    image.pixels.resize(byteCount);
    return SUCCEEDED(converter->CopyPixels(nullptr,image.width*4,static_cast<UINT>(byteCount),image.pixels.data()));
}

UvRect SkinRect(uint32_t skinWidth,uint32_t skinHeight,float x,float y,float width,float height){
    constexpr float inset=0.01f;
    return {(x+inset)/skinWidth,(y+inset)/skinHeight,(x+width-inset)/skinWidth,(y+height-inset)/skinHeight};
}
}

bool Renderer::InitializeFirstPersonArm(const std::filesystem::path& assetRoot,WarningLog& warnings){
    const auto skinPath=assetRoot/L"textures"/L"entities"/L"steve.png";
    if(!std::filesystem::is_regular_file(skinPath)){
        warnings.Add(L"Missing Steve skin texture: "+AbsoluteForMessage(skinPath));
        return true;
    }

    ComPtr<IWICImagingFactory> factory;
    HRESULT hr=CoCreateInstance(CLSID_WICImagingFactory2,nullptr,CLSCTX_INPROC_SERVER,IID_PPV_ARGS(factory.GetAddressOf()));
    if(FAILED(hr))hr=CoCreateInstance(CLSID_WICImagingFactory,nullptr,CLSCTX_INPROC_SERVER,IID_PPV_ARGS(factory.GetAddressOf()));
    if(FAILED(hr))return Fail(L"Create WIC imaging factory for Steve skin",hr);

    DecodedSkin image;
    if(!DecodeSkin(factory.Get(),skinPath,image)){
        warnings.Add(L"Unreadable Steve skin texture: "+AbsoluteForMessage(skinPath));
        return true;
    }
    if(image.width<64||image.height<64){
        warnings.Add(L"Steve skin must be at least 64x64 pixels: "+AbsoluteForMessage(skinPath));
        return true;
    }
    skinWidth_=image.width;
    skinHeight_=image.height;

    D3D11_TEXTURE2D_DESC textureDescription{};
    textureDescription.Width=image.width;
    textureDescription.Height=image.height;
    textureDescription.MipLevels=1;
    textureDescription.ArraySize=1;
    textureDescription.Format=DXGI_FORMAT_R8G8B8A8_UNORM_SRGB;
    textureDescription.SampleDesc.Count=1;
    textureDescription.Usage=D3D11_USAGE_IMMUTABLE;
    textureDescription.BindFlags=D3D11_BIND_SHADER_RESOURCE;
    D3D11_SUBRESOURCE_DATA textureData{};
    textureData.pSysMem=image.pixels.data();
    textureData.SysMemPitch=image.width*4;
    ComPtr<ID3D11Texture2D> texture;
    hr=device_->CreateTexture2D(&textureDescription,&textureData,texture.GetAddressOf());
    if(FAILED(hr)){
        textureDescription.Format=DXGI_FORMAT_R8G8B8A8_UNORM;
        hr=device_->CreateTexture2D(&textureDescription,&textureData,texture.GetAddressOf());
    }
    if(FAILED(hr))return Fail(L"Create Steve skin texture",hr,AbsoluteForMessage(skinPath));
    hr=device_->CreateShaderResourceView(texture.Get(),nullptr,skinTexture_.GetAddressOf());
    if(FAILED(hr))return Fail(L"Create Steve skin shader resource view",hr,AbsoluteForMessage(skinPath));

    ComPtr<ID3DBlob> shaderBlob,errors;
    hr=D3DCompile(kArmShader,std::strlen(kArmShader),nullptr,nullptr,nullptr,"ArmVS","vs_5_0",D3DCOMPILE_ENABLE_STRICTNESS|D3DCOMPILE_OPTIMIZATION_LEVEL3,0,shaderBlob.GetAddressOf(),errors.GetAddressOf());
    if(FAILED(hr)){
        std::wstring detail;
        if(errors){const char* text=static_cast<const char*>(errors->GetBufferPointer());detail=Widen(std::string(text,text+errors->GetBufferSize()));}
        return Fail(L"Compile first-person arm vertex shader",hr,detail);
    }
    hr=device_->CreateVertexShader(shaderBlob->GetBufferPointer(),shaderBlob->GetBufferSize(),nullptr,armVs_.GetAddressOf());
    if(FAILED(hr))return Fail(L"Create first-person arm vertex shader",hr);

    static_assert(sizeof(ArmConstants)%16==0);
    D3D11_BUFFER_DESC constantDescription{};
    constantDescription.ByteWidth=sizeof(ArmConstants);
    constantDescription.Usage=D3D11_USAGE_DEFAULT;
    constantDescription.BindFlags=D3D11_BIND_CONSTANT_BUFFER;
    hr=device_->CreateBuffer(&constantDescription,nullptr,armCb_.GetAddressOf());
    if(FAILED(hr))return Fail(L"Create first-person arm constant buffer",hr);

    D3D11_BUFFER_DESC vertexDescription{};
    vertexDescription.ByteWidth=sizeof(SpriteVertex)*72;
    vertexDescription.Usage=D3D11_USAGE_DYNAMIC;
    vertexDescription.BindFlags=D3D11_BIND_VERTEX_BUFFER;
    vertexDescription.CPUAccessFlags=D3D11_CPU_ACCESS_WRITE;
    hr=device_->CreateBuffer(&vertexDescription,nullptr,armVb_.GetAddressOf());
    return SUCCEEDED(hr)||Fail(L"Create first-person arm vertex buffer",hr);
}

void Renderer::DrawFirstPersonArm(){
    if(!skinTexture_||!armVs_||!armCb_||!armVb_||skinWidth_<64||skinHeight_<64)return;

    std::array<SpriteVertex,72> vertices{};
    size_t output=0;
    const auto appendQuad=[&](const XMFLOAT3& p0,const XMFLOAT3& p1,const XMFLOAT3& p2,const XMFLOAT3& p3,const UvRect& uv){
        constexpr uint32_t white=0xFFFFFFFFu;
        vertices[output++]={p0.x,p0.y,p0.z,uv.u0,uv.v0,white};
        vertices[output++]={p1.x,p1.y,p1.z,uv.u1,uv.v0,white};
        vertices[output++]={p2.x,p2.y,p2.z,uv.u1,uv.v1,white};
        vertices[output++]={p0.x,p0.y,p0.z,uv.u0,uv.v0,white};
        vertices[output++]={p2.x,p2.y,p2.z,uv.u1,uv.v1,white};
        vertices[output++]={p3.x,p3.y,p3.z,uv.u0,uv.v1,white};
    };
    const auto appendCuboid=[&](float width,float height,float depth,float inflate,float textureY){
        const float x0=-width*0.5f-inflate,x1=width*0.5f+inflate;
        const float y0=inflate,y1=-height-inflate;
        const float z0=-depth*0.5f-inflate,z1=depth*0.5f+inflate;
        appendQuad({x0,y0,z0},{x1,y0,z0},{x1,y0,z1},{x0,y0,z1},SkinRect(skinWidth_,skinHeight_,44,textureY,4,4));
        appendQuad({x0,y1,z1},{x1,y1,z1},{x1,y1,z0},{x0,y1,z0},SkinRect(skinWidth_,skinHeight_,48,textureY,4,4));
        appendQuad({x0,y0,z1},{x0,y0,z0},{x0,y1,z0},{x0,y1,z1},SkinRect(skinWidth_,skinHeight_,40,textureY+4,4,12));
        appendQuad({x0,y0,z0},{x1,y0,z0},{x1,y1,z0},{x0,y1,z0},SkinRect(skinWidth_,skinHeight_,44,textureY+4,4,12));
        appendQuad({x1,y0,z0},{x1,y0,z1},{x1,y1,z1},{x1,y1,z0},SkinRect(skinWidth_,skinHeight_,48,textureY+4,4,12));
        appendQuad({x1,y0,z1},{x0,y0,z1},{x0,y1,z1},{x1,y1,z1},SkinRect(skinWidth_,skinHeight_,52,textureY+4,4,12));
    };

    // Steve's base right arm is 4x12x4. The sleeve is the same cuboid, expanded slightly.
    appendCuboid(0.24f,0.72f,0.24f,0.0f,16.0f);
    appendCuboid(0.24f,0.72f,0.24f,0.015f,32.0f);

    const float progress=firstPersonSwinging_?std::clamp(firstPersonSwingProgress_,0.0f,1.0f):0.0f;
    const float rootSwing=std::sin(std::sqrt(progress)*std::numbers::pi_v<float>);
    const float swing=std::sin(progress*std::numbers::pi_v<float>);
    const float verticalWave=std::sin(std::sqrt(progress)*2.0f*std::numbers::pi_v<float>);
    const float pitch=XMConvertToRadians(-18.0f-72.0f*rootSwing);
    const float yaw=XMConvertToRadians(22.0f+38.0f*rootSwing);
    const float roll=XMConvertToRadians(-8.0f+20.0f*swing);
    const float translateX=0.48f-0.20f*rootSwing;
    const float translateY=0.18f+0.09f*verticalWave;
    const float translateZ=0.88f-0.12f*swing;
    const XMMATRIX model=XMMatrixRotationRollPitchYaw(pitch,yaw,roll)*XMMatrixTranslation(translateX,translateY,translateZ);
    const XMMATRIX projection=XMMatrixPerspectiveFovLH(XMConvertToRadians(70.0f),float(width_)/float(height_),0.01f,10.0f);
    ArmConstants constants{};
    XMStoreFloat4x4(&constants.modelProjection,model*projection);
    context_->UpdateSubresource(armCb_.Get(),0,nullptr,&constants,0,0);

    D3D11_MAPPED_SUBRESOURCE mapped{};
    if(FAILED(context_->Map(armVb_.Get(),0,D3D11_MAP_WRITE_DISCARD,0,&mapped)))return;
    std::memcpy(mapped.pData,vertices.data(),sizeof(vertices));
    context_->Unmap(armVb_.Get(),0);

    UINT stride=sizeof(SpriteVertex),offset=0;
    context_->IASetInputLayout(spriteLayout_.Get());
    context_->IASetVertexBuffers(0,1,armVb_.GetAddressOf(),&stride,&offset);
    context_->IASetPrimitiveTopology(D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST);
    context_->VSSetShader(armVs_.Get(),nullptr,0);
    ID3D11Buffer* armConstants=armCb_.Get();
    context_->VSSetConstantBuffers(1,1,&armConstants);
    context_->PSSetShader(spritePs_.Get(),nullptr,0);
    context_->PSSetShaderResources(0,1,skinTexture_.GetAddressOf());
    context_->PSSetSamplers(0,1,sampler_.GetAddressOf());
    context_->OMSetDepthStencilState(skyDepthState_.Get(),0);
    context_->RSSetState(overlayRaster_.Get());
    float blendFactor[4]={};
    context_->OMSetBlendState(alphaBlend_.Get(),blendFactor,0xffffffff);
    context_->Draw(static_cast<UINT>(vertices.size()),0);
    context_->OMSetBlendState(nullptr,nullptr,0xffffffff);
}
}
