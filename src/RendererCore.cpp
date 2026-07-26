#include "Renderer.h"
#include <d3dcompiler.h>
#include <wincodec.h>
#include <cstring>
#include <limits>

using Microsoft::WRL::ComPtr;
using namespace DirectX;

namespace mc {
namespace {
constexpr char kVoxelShader[] = R"(
cbuffer Frame : register(b0) { row_major float4x4 viewProjection; };
struct VSIn { float3 position:POSITION; float2 uv:TEXCOORD0; uint sliceIndex:TEXCOORD1; float4 color:COLOR0; };
struct PSIn { float4 position:SV_POSITION; float3 uv:TEXCOORD0; float4 color:COLOR0; };
PSIn VSMain(VSIn input){ PSIn output; output.position=mul(float4(input.position,1),viewProjection); output.uv=float3(input.uv,input.sliceIndex); output.color=input.color; return output; }
Texture2DArray blockTextures:register(t0); SamplerState pointSampler:register(s0);
float4 PSMain(PSIn input):SV_TARGET { float4 color=blockTextures.Sample(pointSampler,input.uv)*input.color; clip(color.a-0.01); return color; }
)";

constexpr char kColorShader[] = R"(
cbuffer Frame : register(b0) { row_major float4x4 viewProjection; };
struct VSIn { float3 position:POSITION; float4 color:COLOR0; };
struct PSIn { float4 position:SV_POSITION; float4 color:COLOR0; };
PSIn VSMain(VSIn input){ PSIn output; output.position=mul(float4(input.position,1),viewProjection); output.color=input.color; return output; }
float4 PSMain(PSIn input):SV_TARGET { return input.color; }
)";

constexpr char kSpriteShader[] = R"(
cbuffer Frame : register(b0) { row_major float4x4 viewProjection; };
Texture2D spriteTexture:register(t0); SamplerState pointSampler:register(s0);
struct WorldIn { float3 position:POSITION; float2 uv:TEXCOORD0; float4 color:COLOR0; };
struct UiIn { float2 position:POSITION; float2 uv:TEXCOORD0; };
struct PSIn { float4 position:SV_POSITION; float2 uv:TEXCOORD0; float4 color:COLOR0; };
PSIn WorldVS(WorldIn input){ PSIn output; output.position=mul(float4(input.position,1),viewProjection); output.uv=input.uv; output.color=input.color; return output; }
PSIn UiVS(UiIn input){ PSIn output; output.position=float4(input.position,0,1); output.uv=input.uv; output.color=float4(1,1,1,1); return output; }
float4 SpritePS(PSIn input):SV_TARGET { float4 color=spriteTexture.Sample(pointSampler,input.uv)*input.color; clip(color.a-0.01); return color; }
float4 CrosshairPS(PSIn input):SV_TARGET { float alpha=spriteTexture.Sample(pointSampler,input.uv).a; clip(alpha-0.05); return float4(1,1,1,1); }
)";

struct DecodedImage {UINT width{},height{};std::vector<uint8_t> pixels;};

HRESULT Compile(const char* source,const char* entry,const char* target,ComPtr<ID3DBlob>& blob,std::string& errorText){
    blob.Reset();errorText.clear();ComPtr<ID3DBlob> errors;UINT flags=D3DCOMPILE_ENABLE_STRICTNESS;
#ifdef _DEBUG
    flags|=D3DCOMPILE_DEBUG|D3DCOMPILE_SKIP_OPTIMIZATION;
#else
    flags|=D3DCOMPILE_OPTIMIZATION_LEVEL3;
#endif
    const HRESULT hr=D3DCompile(source,std::strlen(source),nullptr,nullptr,nullptr,entry,target,flags,0,blob.GetAddressOf(),errors.GetAddressOf());
    if(errors){const char* text=static_cast<const char*>(errors->GetBufferPointer());errorText.assign(text,text+errors->GetBufferSize());OutputDebugStringA(errorText.c_str());}
    return hr;
}

std::wstring AbsoluteForMessage(const std::filesystem::path& path){std::error_code ec;const auto absolute=std::filesystem::absolute(path,ec);return (ec?path:absolute.lexically_normal()).wstring();}

bool DecodePngRaw(IWICImagingFactory* factory,const std::filesystem::path& path,DecodedImage& image){
    image={};if(path.empty()||!std::filesystem::is_regular_file(path))return false;
    ComPtr<IWICBitmapDecoder> decoder;if(FAILED(factory->CreateDecoderFromFilename(path.c_str(),nullptr,GENERIC_READ,WICDecodeMetadataCacheOnLoad,decoder.GetAddressOf())))return false;
    ComPtr<IWICBitmapFrameDecode> frame;if(FAILED(decoder->GetFrame(0,frame.GetAddressOf())))return false;
    if(FAILED(frame->GetSize(&image.width,&image.height))||image.width==0||image.height==0)return false;
    ComPtr<IWICFormatConverter> converter;if(FAILED(factory->CreateFormatConverter(converter.GetAddressOf())))return false;
    if(FAILED(converter->Initialize(frame.Get(),GUID_WICPixelFormat32bppRGBA,WICBitmapDitherTypeNone,nullptr,0.0,WICBitmapPaletteTypeCustom)))return false;
    const size_t byteCount=static_cast<size_t>(image.width)*image.height*4;if(byteCount>std::numeric_limits<UINT>::max())return false;
    image.pixels.resize(byteCount);return SUCCEEDED(converter->CopyPixels(nullptr,image.width*4,static_cast<UINT>(byteCount),image.pixels.data()));
}

bool DecodePng16(IWICImagingFactory* factory,const std::filesystem::path& path,std::vector<uint8_t>& pixels){
    if(path.empty()||!std::filesystem::is_regular_file(path))return false;
    ComPtr<IWICBitmapDecoder> decoder;if(FAILED(factory->CreateDecoderFromFilename(path.c_str(),nullptr,GENERIC_READ,WICDecodeMetadataCacheOnLoad,decoder.GetAddressOf())))return false;
    ComPtr<IWICBitmapFrameDecode> frame;if(FAILED(decoder->GetFrame(0,frame.GetAddressOf())))return false;
    UINT width=0,height=0;frame->GetSize(&width,&height);ComPtr<IWICBitmapSource> source=frame;ComPtr<IWICBitmapScaler> scaler;
    if(width!=16||height!=16){if(FAILED(factory->CreateBitmapScaler(scaler.GetAddressOf())))return false;if(FAILED(scaler->Initialize(frame.Get(),16,16,WICBitmapInterpolationModeNearestNeighbor)))return false;source=scaler;}
    ComPtr<IWICFormatConverter> converter;if(FAILED(factory->CreateFormatConverter(converter.GetAddressOf())))return false;
    if(FAILED(converter->Initialize(source.Get(),GUID_WICPixelFormat32bppRGBA,WICBitmapDitherTypeNone,nullptr,0.0,WICBitmapPaletteTypeCustom)))return false;
    pixels.resize(16*16*4);return SUCCEEDED(converter->CopyPixels(nullptr,16*4,static_cast<UINT>(pixels.size()),pixels.data()));
}

std::vector<uint8_t> Checker(){std::vector<uint8_t> pixels(16*16*4);for(int y=0;y<16;++y)for(int x=0;x<16;++x){const bool magenta=((x/4+y/4)&1)==0;const size_t index=(y*16+x)*4;pixels[index]=magenta?255:16;pixels[index+1]=0;pixels[index+2]=magenta?255:16;pixels[index+3]=255;}return pixels;}

HRESULT CreateTextureSrv(ID3D11Device* device,const DecodedImage& image,ComPtr<ID3D11ShaderResourceView>& view){
    D3D11_TEXTURE2D_DESC description{};description.Width=image.width;description.Height=image.height;description.MipLevels=1;description.ArraySize=1;description.Format=DXGI_FORMAT_R8G8B8A8_UNORM_SRGB;description.SampleDesc.Count=1;description.Usage=D3D11_USAGE_IMMUTABLE;description.BindFlags=D3D11_BIND_SHADER_RESOURCE;
    D3D11_SUBRESOURCE_DATA initial{};initial.pSysMem=image.pixels.data();initial.SysMemPitch=image.width*4;ComPtr<ID3D11Texture2D> texture;
    HRESULT hr=device->CreateTexture2D(&description,&initial,texture.GetAddressOf());if(FAILED(hr)){description.Format=DXGI_FORMAT_R8G8B8A8_UNORM;hr=device->CreateTexture2D(&description,&initial,texture.GetAddressOf());}
    if(FAILED(hr))return hr;return device->CreateShaderResourceView(texture.Get(),nullptr,view.GetAddressOf());
}
}

bool Renderer::Fail(const wchar_t* stage,HRESULT hr,const std::wstring& detail){
    wchar_t code[16]{};swprintf_s(code,L"0x%08X",static_cast<unsigned>(hr));lastError_=stage;lastError_+=L" failed (";lastError_+=code;lastError_+=L")";
    LPWSTR systemText=nullptr;const DWORD count=FormatMessageW(FORMAT_MESSAGE_ALLOCATE_BUFFER|FORMAT_MESSAGE_FROM_SYSTEM|FORMAT_MESSAGE_IGNORE_INSERTS,nullptr,static_cast<DWORD>(hr),MAKELANGID(LANG_NEUTRAL,SUBLANG_DEFAULT),reinterpret_cast<LPWSTR>(&systemText),0,nullptr);
    if(count&&systemText){lastError_+=L"\n";lastError_+=systemText;LocalFree(systemText);}if(!detail.empty()){lastError_+=L"\n";lastError_+=detail;}OutputDebugStringW(lastError_.c_str());OutputDebugStringW(L"\n");return false;
}

bool Renderer::Initialize(HWND window,int width,int height,const TexturePack& textures,const std::filesystem::path& assetRoot,WarningLog& warnings){
    lastError_.clear();width_=std::max(1,width);height_=std::max(1,height);destroyStages_=textures.DestroyStages();
    if(!CreateDevice(window))return false;if(!CreateTargets(width_,height_))return false;if(!CreateShaders())return false;if(!CreateTextureArray(textures,warnings))return false;if(!CreateStandaloneTextures(assetRoot,warnings))return false;if(!CreateStates())return false;return true;
}

bool Renderer::CreateDevice(HWND window){
    DXGI_SWAP_CHAIN_DESC swapDescription{};swapDescription.BufferCount=2;swapDescription.BufferDesc.Width=width_;swapDescription.BufferDesc.Height=height_;swapDescription.BufferDesc.Format=DXGI_FORMAT_R8G8B8A8_UNORM;swapDescription.BufferUsage=DXGI_USAGE_RENDER_TARGET_OUTPUT;swapDescription.OutputWindow=window;swapDescription.SampleDesc.Count=1;swapDescription.Windowed=TRUE;swapDescription.SwapEffect=DXGI_SWAP_EFFECT_DISCARD;
    UINT flags=0;
#ifdef _DEBUG
    flags|=D3D11_CREATE_DEVICE_DEBUG;
#endif
    D3D_FEATURE_LEVEL levels[]={D3D_FEATURE_LEVEL_11_1,D3D_FEATURE_LEVEL_11_0};D3D_FEATURE_LEVEL level11[]={D3D_FEATURE_LEVEL_11_0};D3D_FEATURE_LEVEL created{};
    const auto attempt=[&](D3D_DRIVER_TYPE driver,const D3D_FEATURE_LEVEL* requested,UINT count,UINT createFlags){swapChain_.Reset();device_.Reset();context_.Reset();return D3D11CreateDeviceAndSwapChain(nullptr,driver,nullptr,createFlags,requested,count,D3D11_SDK_VERSION,&swapDescription,swapChain_.GetAddressOf(),device_.GetAddressOf(),&created,context_.GetAddressOf());};
    HRESULT hr=attempt(D3D_DRIVER_TYPE_HARDWARE,levels,2,flags);if(hr==E_INVALIDARG)hr=attempt(D3D_DRIVER_TYPE_HARDWARE,level11,1,flags);if(FAILED(hr)){hr=attempt(D3D_DRIVER_TYPE_WARP,levels,2,0);if(hr==E_INVALIDARG)hr=attempt(D3D_DRIVER_TYPE_WARP,level11,1,0);}return SUCCEEDED(hr)||Fail(L"D3D11CreateDeviceAndSwapChain",hr,L"Update the graphics driver or enable the Windows Direct3D components.");
}

bool Renderer::CreateTargets(int width,int height){
    rtv_.Reset();dsv_.Reset();depth_.Reset();ComPtr<ID3D11Texture2D> backBuffer;HRESULT hr=swapChain_->GetBuffer(0,IID_PPV_ARGS(backBuffer.GetAddressOf()));if(FAILED(hr))return Fail(L"IDXGISwapChain::GetBuffer",hr);
    hr=device_->CreateRenderTargetView(backBuffer.Get(),nullptr,rtv_.GetAddressOf());if(FAILED(hr))return Fail(L"ID3D11Device::CreateRenderTargetView",hr);
    D3D11_TEXTURE2D_DESC depthDescription{};depthDescription.Width=width;depthDescription.Height=height;depthDescription.MipLevels=1;depthDescription.ArraySize=1;depthDescription.Format=DXGI_FORMAT_D24_UNORM_S8_UINT;depthDescription.SampleDesc.Count=1;depthDescription.BindFlags=D3D11_BIND_DEPTH_STENCIL;
    hr=device_->CreateTexture2D(&depthDescription,nullptr,depth_.GetAddressOf());if(FAILED(hr))return Fail(L"ID3D11Device::CreateTexture2D (depth)",hr);hr=device_->CreateDepthStencilView(depth_.Get(),nullptr,dsv_.GetAddressOf());return SUCCEEDED(hr)||Fail(L"ID3D11Device::CreateDepthStencilView",hr);
}

void Renderer::Resize(int width,int height){if(!swapChain_||width<=0||height<=0)return;width_=width;height_=height;context_->OMSetRenderTargets(0,nullptr,nullptr);rtv_.Reset();dsv_.Reset();depth_.Reset();if(SUCCEEDED(swapChain_->ResizeBuffers(0,width,height,DXGI_FORMAT_UNKNOWN,0)))CreateTargets(width,height);}

bool Renderer::CreateShaders(){
    ComPtr<ID3DBlob> vertexBlob,pixelBlob;std::string errors;HRESULT hr=Compile(kVoxelShader,"VSMain","vs_5_0",vertexBlob,errors);if(FAILED(hr))return Fail(L"D3DCompile voxel vertex shader",hr,Widen(errors));
    hr=Compile(kVoxelShader,"PSMain","ps_5_0",pixelBlob,errors);if(FAILED(hr))return Fail(L"D3DCompile voxel pixel shader",hr,Widen(errors));
    hr=device_->CreateVertexShader(vertexBlob->GetBufferPointer(),vertexBlob->GetBufferSize(),nullptr,voxelVs_.GetAddressOf());if(FAILED(hr))return Fail(L"CreateVertexShader (voxel)",hr);hr=device_->CreatePixelShader(pixelBlob->GetBufferPointer(),pixelBlob->GetBufferSize(),nullptr,voxelPs_.GetAddressOf());if(FAILED(hr))return Fail(L"CreatePixelShader (voxel)",hr);
    D3D11_INPUT_ELEMENT_DESC voxelElements[]={{"POSITION",0,DXGI_FORMAT_R32G32B32_FLOAT,0,0,D3D11_INPUT_PER_VERTEX_DATA,0},{"TEXCOORD",0,DXGI_FORMAT_R32G32_FLOAT,0,12,D3D11_INPUT_PER_VERTEX_DATA,0},{"TEXCOORD",1,DXGI_FORMAT_R32_UINT,0,20,D3D11_INPUT_PER_VERTEX_DATA,0},{"COLOR",0,DXGI_FORMAT_R8G8B8A8_UNORM,0,24,D3D11_INPUT_PER_VERTEX_DATA,0}};
    hr=device_->CreateInputLayout(voxelElements,4,vertexBlob->GetBufferPointer(),vertexBlob->GetBufferSize(),voxelLayout_.GetAddressOf());if(FAILED(hr))return Fail(L"CreateInputLayout (voxel)",hr);

    hr=Compile(kColorShader,"VSMain","vs_5_0",vertexBlob,errors);if(FAILED(hr))return Fail(L"D3DCompile color vertex shader",hr,Widen(errors));hr=Compile(kColorShader,"PSMain","ps_5_0",pixelBlob,errors);if(FAILED(hr))return Fail(L"D3DCompile color pixel shader",hr,Widen(errors));
    hr=device_->CreateVertexShader(vertexBlob->GetBufferPointer(),vertexBlob->GetBufferSize(),nullptr,colorVs_.GetAddressOf());if(FAILED(hr))return Fail(L"CreateVertexShader (color)",hr);hr=device_->CreatePixelShader(pixelBlob->GetBufferPointer(),pixelBlob->GetBufferSize(),nullptr,colorPs_.GetAddressOf());if(FAILED(hr))return Fail(L"CreatePixelShader (color)",hr);
    D3D11_INPUT_ELEMENT_DESC colorElements[]={{"POSITION",0,DXGI_FORMAT_R32G32B32_FLOAT,0,0,D3D11_INPUT_PER_VERTEX_DATA,0},{"COLOR",0,DXGI_FORMAT_R8G8B8A8_UNORM,0,12,D3D11_INPUT_PER_VERTEX_DATA,0}};
    hr=device_->CreateInputLayout(colorElements,2,vertexBlob->GetBufferPointer(),vertexBlob->GetBufferSize(),colorLayout_.GetAddressOf());if(FAILED(hr))return Fail(L"CreateInputLayout (color)",hr);

    hr=Compile(kSpriteShader,"WorldVS","vs_5_0",vertexBlob,errors);if(FAILED(hr))return Fail(L"D3DCompile world sprite vertex shader",hr,Widen(errors));hr=Compile(kSpriteShader,"SpritePS","ps_5_0",pixelBlob,errors);if(FAILED(hr))return Fail(L"D3DCompile world sprite pixel shader",hr,Widen(errors));
    hr=device_->CreateVertexShader(vertexBlob->GetBufferPointer(),vertexBlob->GetBufferSize(),nullptr,spriteVs_.GetAddressOf());if(FAILED(hr))return Fail(L"CreateVertexShader (sprite)",hr);hr=device_->CreatePixelShader(pixelBlob->GetBufferPointer(),pixelBlob->GetBufferSize(),nullptr,spritePs_.GetAddressOf());if(FAILED(hr))return Fail(L"CreatePixelShader (sprite)",hr);
    D3D11_INPUT_ELEMENT_DESC spriteElements[]={{"POSITION",0,DXGI_FORMAT_R32G32B32_FLOAT,0,0,D3D11_INPUT_PER_VERTEX_DATA,0},{"TEXCOORD",0,DXGI_FORMAT_R32G32_FLOAT,0,12,D3D11_INPUT_PER_VERTEX_DATA,0},{"COLOR",0,DXGI_FORMAT_R8G8B8A8_UNORM,0,20,D3D11_INPUT_PER_VERTEX_DATA,0}};
    hr=device_->CreateInputLayout(spriteElements,3,vertexBlob->GetBufferPointer(),vertexBlob->GetBufferSize(),spriteLayout_.GetAddressOf());if(FAILED(hr))return Fail(L"CreateInputLayout (sprite)",hr);

    hr=Compile(kSpriteShader,"UiVS","vs_5_0",vertexBlob,errors);if(FAILED(hr))return Fail(L"D3DCompile UI vertex shader",hr,Widen(errors));hr=Compile(kSpriteShader,"CrosshairPS","ps_5_0",pixelBlob,errors);if(FAILED(hr))return Fail(L"D3DCompile crosshair pixel shader",hr,Widen(errors));
    hr=device_->CreateVertexShader(vertexBlob->GetBufferPointer(),vertexBlob->GetBufferSize(),nullptr,uiVs_.GetAddressOf());if(FAILED(hr))return Fail(L"CreateVertexShader (UI)",hr);hr=device_->CreatePixelShader(pixelBlob->GetBufferPointer(),pixelBlob->GetBufferSize(),nullptr,uiPs_.GetAddressOf());if(FAILED(hr))return Fail(L"CreatePixelShader (UI)",hr);
    D3D11_INPUT_ELEMENT_DESC uiElements[]={{"POSITION",0,DXGI_FORMAT_R32G32_FLOAT,0,0,D3D11_INPUT_PER_VERTEX_DATA,0},{"TEXCOORD",0,DXGI_FORMAT_R32G32_FLOAT,0,8,D3D11_INPUT_PER_VERTEX_DATA,0}};
    hr=device_->CreateInputLayout(uiElements,2,vertexBlob->GetBufferPointer(),vertexBlob->GetBufferSize(),uiLayout_.GetAddressOf());if(FAILED(hr))return Fail(L"CreateInputLayout (UI)",hr);

    D3D11_BUFFER_DESC constantBuffer{};constantBuffer.ByteWidth=sizeof(FrameConstants);constantBuffer.Usage=D3D11_USAGE_DEFAULT;constantBuffer.BindFlags=D3D11_BIND_CONSTANT_BUFFER;hr=device_->CreateBuffer(&constantBuffer,nullptr,frameCb_.GetAddressOf());return SUCCEEDED(hr)||Fail(L"CreateBuffer (frame constants)",hr);
}

bool Renderer::CreateTextureArray(const TexturePack& textures,WarningLog& warnings){
    ComPtr<IWICImagingFactory> factory;HRESULT hr=CoCreateInstance(CLSID_WICImagingFactory2,nullptr,CLSCTX_INPROC_SERVER,IID_PPV_ARGS(factory.GetAddressOf()));if(FAILED(hr))hr=CoCreateInstance(CLSID_WICImagingFactory,nullptr,CLSCTX_INPROC_SERVER,IID_PPV_ARGS(factory.GetAddressOf()));if(FAILED(hr))return Fail(L"Create WIC imaging factory",hr);
    const auto& paths=textures.SlicePaths();if(paths.empty())return Fail(L"Create texture array",E_FAIL,L"No texture slices were discovered.");if(paths.size()>D3D11_REQ_TEXTURE2D_ARRAY_AXIS_DIMENSION)return Fail(L"Create texture array",E_INVALIDARG,L"The texture pack contains more texture slices than Direct3D 11 permits.");
    std::vector<std::vector<uint8_t>> images(paths.size());const auto checker=Checker();for(size_t index=0;index<paths.size();++index){if(!DecodePng16(factory.Get(),paths[index],images[index])){images[index]=checker;if(!paths[index].empty())warnings.Add(L"Unreadable block texture: "+AbsoluteForMessage(paths[index]));}}
    D3D11_TEXTURE2D_DESC description{};description.Width=16;description.Height=16;description.MipLevels=1;description.ArraySize=static_cast<UINT>(images.size());description.Format=DXGI_FORMAT_R8G8B8A8_UNORM_SRGB;description.SampleDesc.Count=1;description.Usage=D3D11_USAGE_IMMUTABLE;description.BindFlags=D3D11_BIND_SHADER_RESOURCE;
    std::vector<D3D11_SUBRESOURCE_DATA> initial(images.size());for(size_t index=0;index<images.size();++index){initial[index].pSysMem=images[index].data();initial[index].SysMemPitch=64;}
    ComPtr<ID3D11Texture2D> texture;hr=device_->CreateTexture2D(&description,initial.data(),texture.GetAddressOf());if(FAILED(hr)){description.Format=DXGI_FORMAT_R8G8B8A8_UNORM;hr=device_->CreateTexture2D(&description,initial.data(),texture.GetAddressOf());}if(FAILED(hr))return Fail(L"CreateTexture2D (block texture array)",hr);
    D3D11_SHADER_RESOURCE_VIEW_DESC viewDescription{};viewDescription.Format=description.Format;viewDescription.ViewDimension=D3D11_SRV_DIMENSION_TEXTURE2DARRAY;viewDescription.Texture2DArray.MipLevels=1;viewDescription.Texture2DArray.ArraySize=description.ArraySize;hr=device_->CreateShaderResourceView(texture.Get(),&viewDescription,textureArray_.GetAddressOf());return SUCCEEDED(hr)||Fail(L"CreateShaderResourceView (block texture array)",hr);
}

bool Renderer::CreateStandaloneTextures(const std::filesystem::path& assetRoot,WarningLog& warnings){
    ComPtr<IWICImagingFactory> factory;HRESULT hr=CoCreateInstance(CLSID_WICImagingFactory2,nullptr,CLSCTX_INPROC_SERVER,IID_PPV_ARGS(factory.GetAddressOf()));if(FAILED(hr))hr=CoCreateInstance(CLSID_WICImagingFactory,nullptr,CLSCTX_INPROC_SERVER,IID_PPV_ARGS(factory.GetAddressOf()));if(FAILED(hr))return Fail(L"Create WIC imaging factory for standalone textures",hr);
    const auto load=[&](const wchar_t* label,const std::filesystem::path& path,ComPtr<ID3D11ShaderResourceView>& view,uint32_t* width=nullptr,uint32_t* height=nullptr)->bool{
        if(!std::filesystem::is_regular_file(path)){warnings.Add(std::wstring(L"Missing ")+label+L": "+AbsoluteForMessage(path));return true;}
        DecodedImage image;if(!DecodePngRaw(factory.Get(),path,image)){warnings.Add(std::wstring(L"Unreadable ")+label+L": "+AbsoluteForMessage(path));return true;}
        const HRESULT result=CreateTextureSrv(device_.Get(),image,view);if(FAILED(result)){const std::wstring stage=std::wstring(L"Create ")+label;return Fail(stage.c_str(),result,AbsoluteForMessage(path));}if(width)*width=image.width;if(height)*height=image.height;return true;
    };
    if(!load(L"GUI icons texture",assetRoot/L"textures"/L"gui"/L"icons.png",iconsTexture_,&iconsWidth_,&iconsHeight_))return false;
    auto environmentDirectory=assetRoot/L"enviroment";if(!std::filesystem::is_directory(environmentDirectory)&&std::filesystem::is_directory(assetRoot/L"environment"))environmentDirectory=assetRoot/L"environment";
    if(!load(L"sun texture",environmentDirectory/L"sun.png",sunTexture_))return false;if(!load(L"cloud texture",environmentDirectory/L"clouds.png",cloudsTexture_))return false;return true;
}

bool Renderer::CreateStates(){
    D3D11_SAMPLER_DESC samplerDescription{};samplerDescription.Filter=D3D11_FILTER_MIN_MAG_MIP_POINT;samplerDescription.AddressU=samplerDescription.AddressV=samplerDescription.AddressW=D3D11_TEXTURE_ADDRESS_WRAP;samplerDescription.MaxAnisotropy=1;samplerDescription.ComparisonFunc=D3D11_COMPARISON_NEVER;samplerDescription.MinLOD=0.0f;samplerDescription.MaxLOD=D3D11_FLOAT32_MAX;HRESULT hr=device_->CreateSamplerState(&samplerDescription,sampler_.GetAddressOf());if(FAILED(hr))return Fail(L"CreateSamplerState",hr);
    D3D11_RASTERIZER_DESC rasterDescription{};rasterDescription.FillMode=D3D11_FILL_SOLID;rasterDescription.CullMode=D3D11_CULL_BACK;rasterDescription.FrontCounterClockwise=FALSE;rasterDescription.DepthClipEnable=TRUE;hr=device_->CreateRasterizerState(&rasterDescription,solidRaster_.GetAddressOf());if(FAILED(hr))return Fail(L"CreateRasterizerState (solid)",hr);
    rasterDescription.CullMode=D3D11_CULL_NONE;rasterDescription.DepthBias=-2;rasterDescription.SlopeScaledDepthBias=-1.0f;hr=device_->CreateRasterizerState(&rasterDescription,overlayRaster_.GetAddressOf());if(FAILED(hr))return Fail(L"CreateRasterizerState (overlay)",hr);rasterDescription.DepthBias=-4;hr=device_->CreateRasterizerState(&rasterDescription,lineRaster_.GetAddressOf());if(FAILED(hr))return Fail(L"CreateRasterizerState (outline)",hr);
    D3D11_BLEND_DESC alphaDescription{};alphaDescription.RenderTarget[0].BlendEnable=TRUE;alphaDescription.RenderTarget[0].SrcBlend=D3D11_BLEND_SRC_ALPHA;alphaDescription.RenderTarget[0].DestBlend=D3D11_BLEND_INV_SRC_ALPHA;alphaDescription.RenderTarget[0].BlendOp=D3D11_BLEND_OP_ADD;alphaDescription.RenderTarget[0].SrcBlendAlpha=D3D11_BLEND_ONE;alphaDescription.RenderTarget[0].DestBlendAlpha=D3D11_BLEND_ZERO;alphaDescription.RenderTarget[0].BlendOpAlpha=D3D11_BLEND_OP_ADD;alphaDescription.RenderTarget[0].RenderTargetWriteMask=D3D11_COLOR_WRITE_ENABLE_ALL;hr=device_->CreateBlendState(&alphaDescription,alphaBlend_.GetAddressOf());if(FAILED(hr))return Fail(L"CreateBlendState (alpha)",hr);
    D3D11_BLEND_DESC invertDescription{};invertDescription.RenderTarget[0].BlendEnable=TRUE;invertDescription.RenderTarget[0].SrcBlend=D3D11_BLEND_INV_DEST_COLOR;invertDescription.RenderTarget[0].DestBlend=D3D11_BLEND_ZERO;invertDescription.RenderTarget[0].BlendOp=D3D11_BLEND_OP_ADD;invertDescription.RenderTarget[0].SrcBlendAlpha=D3D11_BLEND_ONE;invertDescription.RenderTarget[0].DestBlendAlpha=D3D11_BLEND_ZERO;invertDescription.RenderTarget[0].BlendOpAlpha=D3D11_BLEND_OP_ADD;invertDescription.RenderTarget[0].RenderTargetWriteMask=D3D11_COLOR_WRITE_ENABLE_ALL;hr=device_->CreateBlendState(&invertDescription,invertBlend_.GetAddressOf());if(FAILED(hr))return Fail(L"CreateBlendState (crosshair invert)",hr);
    D3D11_DEPTH_STENCIL_DESC depthDescription{};depthDescription.DepthEnable=TRUE;depthDescription.DepthWriteMask=D3D11_DEPTH_WRITE_MASK_ALL;depthDescription.DepthFunc=D3D11_COMPARISON_LESS_EQUAL;hr=device_->CreateDepthStencilState(&depthDescription,depthState_.GetAddressOf());if(FAILED(hr))return Fail(L"CreateDepthStencilState",hr);depthDescription.DepthEnable=FALSE;depthDescription.DepthWriteMask=D3D11_DEPTH_WRITE_MASK_ZERO;depthDescription.DepthFunc=D3D11_COMPARISON_ALWAYS;hr=device_->CreateDepthStencilState(&depthDescription,skyDepthState_.GetAddressOf());if(FAILED(hr))return Fail(L"CreateDepthStencilState (sky/UI)",hr);
    D3D11_BUFFER_DESC vertexBuffer{};vertexBuffer.Usage=D3D11_USAGE_DYNAMIC;vertexBuffer.BindFlags=D3D11_BIND_VERTEX_BUFFER;vertexBuffer.CPUAccessFlags=D3D11_CPU_ACCESS_WRITE;vertexBuffer.ByteWidth=sizeof(VoxelVertex)*24;hr=device_->CreateBuffer(&vertexBuffer,nullptr,overlayVb_.GetAddressOf());if(FAILED(hr))return Fail(L"CreateBuffer (crack vertices)",hr);
    D3D11_BUFFER_DESC indexBuffer{};indexBuffer.ByteWidth=sizeof(uint32_t)*36;indexBuffer.Usage=D3D11_USAGE_DYNAMIC;indexBuffer.BindFlags=D3D11_BIND_INDEX_BUFFER;indexBuffer.CPUAccessFlags=D3D11_CPU_ACCESS_WRITE;hr=device_->CreateBuffer(&indexBuffer,nullptr,overlayIb_.GetAddressOf());if(FAILED(hr))return Fail(L"CreateBuffer (crack indices)",hr);
    vertexBuffer.ByteWidth=sizeof(ColorVertex)*72;hr=device_->CreateBuffer(&vertexBuffer,nullptr,lineVb_.GetAddressOf());if(FAILED(hr))return Fail(L"CreateBuffer (outline vertices)",hr);vertexBuffer.ByteWidth=sizeof(SpriteVertex)*12;hr=device_->CreateBuffer(&vertexBuffer,nullptr,environmentVb_.GetAddressOf());if(FAILED(hr))return Fail(L"CreateBuffer (environment vertices)",hr);vertexBuffer.ByteWidth=sizeof(UiVertex)*6;hr=device_->CreateBuffer(&vertexBuffer,nullptr,uiVb_.GetAddressOf());return SUCCEEDED(hr)||Fail(L"CreateBuffer (UI vertices)",hr);
}
}
