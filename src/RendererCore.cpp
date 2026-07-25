#include "Renderer.h"
#include <d3dcompiler.h>
#include <wincodec.h>
#include <cstring>

using Microsoft::WRL::ComPtr;
using namespace DirectX;
namespace mc {
namespace {
constexpr char kVoxelShader[] = R"(
cbuffer Frame : register(b0) { row_major float4x4 viewProjection; };
struct VSIn { float3 position:POSITION; float2 uv:TEXCOORD0; uint sliceIndex:TEXCOORD1; float4 color:COLOR0; };
struct PSIn { float4 position:SV_POSITION; float3 uv:TEXCOORD0; float4 color:COLOR0; };
PSIn VSMain(VSIn i){ PSIn o; o.position=mul(float4(i.position,1),viewProjection); o.uv=float3(i.uv,i.sliceIndex); o.color=i.color; return o; }
Texture2DArray tex:register(t0); SamplerState pointSampler:register(s0);
float4 PSMain(PSIn i):SV_TARGET { float4 c=tex.Sample(pointSampler,i.uv)*i.color; clip(c.a-0.01); return c; }
)";
constexpr char kColorShader[] = R"(
cbuffer Frame : register(b0) { row_major float4x4 viewProjection; };
struct VSIn { float3 position:POSITION; float4 color:COLOR0; };
struct PSIn { float4 position:SV_POSITION; float4 color:COLOR0; };
PSIn VSMain(VSIn i){PSIn o;o.position=mul(float4(i.position,1),viewProjection);o.color=i.color;return o;}
float4 PSMain(PSIn i):SV_TARGET{return i.color;}
)";
HRESULT Compile(const char* source,const char* entry,const char* target,ComPtr<ID3DBlob>& blob,std::string& errorText){
    blob.Reset();errorText.clear();ComPtr<ID3DBlob> errors;UINT flags=D3DCOMPILE_ENABLE_STRICTNESS;
#ifdef _DEBUG
    flags|=D3DCOMPILE_DEBUG|D3DCOMPILE_SKIP_OPTIMIZATION;
#else
    flags|=D3DCOMPILE_OPTIMIZATION_LEVEL3;
#endif
    HRESULT hr=D3DCompile(source,std::strlen(source),nullptr,nullptr,nullptr,entry,target,flags,0,blob.GetAddressOf(),errors.GetAddressOf());
    if(errors){const char* text=static_cast<const char*>(errors->GetBufferPointer());errorText.assign(text,text+errors->GetBufferSize());OutputDebugStringA(errorText.c_str());}
    return hr;
}
std::vector<uint8_t> Checker(){std::vector<uint8_t> p(16*16*4);for(int y=0;y<16;++y)for(int x=0;x<16;++x){bool m=((x/4+y/4)&1)==0;size_t i=(y*16+x)*4;p[i]=m?255:16;p[i+1]=0;p[i+2]=m?255:16;p[i+3]=255;}return p;}
bool DecodePng(IWICImagingFactory* factory,const std::filesystem::path& path,std::vector<uint8_t>& pixels){
    if(path.empty()||!std::filesystem::exists(path))return false;
    ComPtr<IWICBitmapDecoder> decoder;if(FAILED(factory->CreateDecoderFromFilename(path.c_str(),nullptr,GENERIC_READ,WICDecodeMetadataCacheOnLoad,decoder.GetAddressOf())))return false;
    ComPtr<IWICBitmapFrameDecode> frame;if(FAILED(decoder->GetFrame(0,frame.GetAddressOf())))return false;
    UINT w=0,h=0;frame->GetSize(&w,&h);ComPtr<IWICBitmapSource> source=frame;
    ComPtr<IWICBitmapScaler> scaler;if(w!=16||h!=16){if(FAILED(factory->CreateBitmapScaler(scaler.GetAddressOf())))return false;if(FAILED(scaler->Initialize(frame.Get(),16,16,WICBitmapInterpolationModeNearestNeighbor)))return false;source=scaler;}
    ComPtr<IWICFormatConverter> converter;if(FAILED(factory->CreateFormatConverter(converter.GetAddressOf())))return false;
    if(FAILED(converter->Initialize(source.Get(),GUID_WICPixelFormat32bppRGBA,WICBitmapDitherTypeNone,nullptr,0,WICBitmapPaletteTypeCustom)))return false;
    pixels.resize(16*16*4);return SUCCEEDED(converter->CopyPixels(nullptr,16*4,static_cast<UINT>(pixels.size()),pixels.data()));
}
}

bool Renderer::Fail(const wchar_t* stage,HRESULT hr,const std::wstring& detail){
    wchar_t code[16]{};swprintf_s(code,L"0x%08X",static_cast<unsigned>(hr));
    lastError_=stage;lastError_+=L" failed (";lastError_+=code;lastError_+=L")";
    LPWSTR systemText=nullptr;DWORD n=FormatMessageW(FORMAT_MESSAGE_ALLOCATE_BUFFER|FORMAT_MESSAGE_FROM_SYSTEM|FORMAT_MESSAGE_IGNORE_INSERTS,nullptr,static_cast<DWORD>(hr),MAKELANGID(LANG_NEUTRAL,SUBLANG_DEFAULT),reinterpret_cast<LPWSTR>(&systemText),0,nullptr);
    if(n&&systemText){lastError_+=L"\n";lastError_+=systemText;LocalFree(systemText);}
    if(!detail.empty()){lastError_+=L"\n";lastError_+=detail;}
    OutputDebugStringW(lastError_.c_str());OutputDebugStringW(L"\n");return false;
}

bool Renderer::Initialize(HWND window,int width,int height,const TexturePack& textures,WarningLog& warnings){
    lastError_.clear();width_=std::max(1,width);height_=std::max(1,height);destroyStages_=textures.DestroyStages();
    if(!CreateDevice(window))return false;
    if(!CreateTargets(width_,height_))return false;
    if(!CreateShaders())return false;
    if(!CreateTextureArray(textures,warnings))return false;
    if(!CreateStates())return false;
    return true;
}
bool Renderer::CreateDevice(HWND window){
    DXGI_SWAP_CHAIN_DESC sd{};sd.BufferCount=2;sd.BufferDesc.Width=width_;sd.BufferDesc.Height=height_;sd.BufferDesc.Format=DXGI_FORMAT_R8G8B8A8_UNORM;sd.BufferUsage=DXGI_USAGE_RENDER_TARGET_OUTPUT;sd.OutputWindow=window;sd.SampleDesc.Count=1;sd.Windowed=TRUE;sd.SwapEffect=DXGI_SWAP_EFFECT_DISCARD;
    UINT flags=0;
#ifdef _DEBUG
    flags|=D3D11_CREATE_DEVICE_DEBUG;
#endif
    D3D_FEATURE_LEVEL levels[]={D3D_FEATURE_LEVEL_11_1,D3D_FEATURE_LEVEL_11_0};D3D_FEATURE_LEVEL level11[]={D3D_FEATURE_LEVEL_11_0};D3D_FEATURE_LEVEL made{};
    auto attempt=[&](D3D_DRIVER_TYPE driver,const D3D_FEATURE_LEVEL* requested,UINT count,UINT createFlags){swapChain_.Reset();device_.Reset();context_.Reset();return D3D11CreateDeviceAndSwapChain(nullptr,driver,nullptr,createFlags,requested,count,D3D11_SDK_VERSION,&sd,swapChain_.GetAddressOf(),device_.GetAddressOf(),&made,context_.GetAddressOf());};
    HRESULT hr=attempt(D3D_DRIVER_TYPE_HARDWARE,levels,2,flags);
    if(hr==E_INVALIDARG)hr=attempt(D3D_DRIVER_TYPE_HARDWARE,level11,1,flags);
    if(FAILED(hr)){hr=attempt(D3D_DRIVER_TYPE_WARP,levels,2,0);if(hr==E_INVALIDARG)hr=attempt(D3D_DRIVER_TYPE_WARP,level11,1,0);}
    return SUCCEEDED(hr)||Fail(L"D3D11CreateDeviceAndSwapChain",hr,L"Update the graphics driver or enable the Windows Direct3D components.");
}
bool Renderer::CreateTargets(int width,int height){
    rtv_.Reset();dsv_.Reset();depth_.Reset();ComPtr<ID3D11Texture2D> back;HRESULT hr=swapChain_->GetBuffer(0,IID_PPV_ARGS(back.GetAddressOf()));if(FAILED(hr))return Fail(L"IDXGISwapChain::GetBuffer",hr);
    hr=device_->CreateRenderTargetView(back.Get(),nullptr,rtv_.GetAddressOf());if(FAILED(hr))return Fail(L"ID3D11Device::CreateRenderTargetView",hr);
    D3D11_TEXTURE2D_DESC dd{};dd.Width=width;dd.Height=height;dd.MipLevels=1;dd.ArraySize=1;dd.Format=DXGI_FORMAT_D24_UNORM_S8_UINT;dd.SampleDesc.Count=1;dd.BindFlags=D3D11_BIND_DEPTH_STENCIL;
    hr=device_->CreateTexture2D(&dd,nullptr,depth_.GetAddressOf());if(FAILED(hr))return Fail(L"ID3D11Device::CreateTexture2D (depth)",hr);
    hr=device_->CreateDepthStencilView(depth_.Get(),nullptr,dsv_.GetAddressOf());return SUCCEEDED(hr)||Fail(L"ID3D11Device::CreateDepthStencilView",hr);
}
void Renderer::Resize(int width,int height){if(!swapChain_||width<=0||height<=0)return;width_=width;height_=height;context_->OMSetRenderTargets(0,nullptr,nullptr);rtv_.Reset();dsv_.Reset();depth_.Reset();if(SUCCEEDED(swapChain_->ResizeBuffers(0,width,height,DXGI_FORMAT_UNKNOWN,0)))CreateTargets(width,height);}
bool Renderer::CreateShaders(){
    ComPtr<ID3DBlob> vs,ps;std::string errors;HRESULT hr=Compile(kVoxelShader,"VSMain","vs_5_0",vs,errors);if(FAILED(hr))return Fail(L"D3DCompile voxel vertex shader",hr,Widen(errors));
    hr=Compile(kVoxelShader,"PSMain","ps_5_0",ps,errors);if(FAILED(hr))return Fail(L"D3DCompile voxel pixel shader",hr,Widen(errors));
    hr=device_->CreateVertexShader(vs->GetBufferPointer(),vs->GetBufferSize(),nullptr,voxelVs_.GetAddressOf());if(FAILED(hr))return Fail(L"CreateVertexShader (voxel)",hr);
    hr=device_->CreatePixelShader(ps->GetBufferPointer(),ps->GetBufferSize(),nullptr,voxelPs_.GetAddressOf());if(FAILED(hr))return Fail(L"CreatePixelShader (voxel)",hr);
    D3D11_INPUT_ELEMENT_DESC vl[]={{"POSITION",0,DXGI_FORMAT_R32G32B32_FLOAT,0,0,D3D11_INPUT_PER_VERTEX_DATA,0},{"TEXCOORD",0,DXGI_FORMAT_R32G32_FLOAT,0,12,D3D11_INPUT_PER_VERTEX_DATA,0},{"TEXCOORD",1,DXGI_FORMAT_R32_UINT,0,20,D3D11_INPUT_PER_VERTEX_DATA,0},{"COLOR",0,DXGI_FORMAT_R8G8B8A8_UNORM,0,24,D3D11_INPUT_PER_VERTEX_DATA,0}};
    hr=device_->CreateInputLayout(vl,4,vs->GetBufferPointer(),vs->GetBufferSize(),voxelLayout_.GetAddressOf());if(FAILED(hr))return Fail(L"CreateInputLayout (voxel)",hr);
    hr=Compile(kColorShader,"VSMain","vs_5_0",vs,errors);if(FAILED(hr))return Fail(L"D3DCompile color vertex shader",hr,Widen(errors));
    hr=Compile(kColorShader,"PSMain","ps_5_0",ps,errors);if(FAILED(hr))return Fail(L"D3DCompile color pixel shader",hr,Widen(errors));
    hr=device_->CreateVertexShader(vs->GetBufferPointer(),vs->GetBufferSize(),nullptr,colorVs_.GetAddressOf());if(FAILED(hr))return Fail(L"CreateVertexShader (color)",hr);
    hr=device_->CreatePixelShader(ps->GetBufferPointer(),ps->GetBufferSize(),nullptr,colorPs_.GetAddressOf());if(FAILED(hr))return Fail(L"CreatePixelShader (color)",hr);
    D3D11_INPUT_ELEMENT_DESC cl[]={{"POSITION",0,DXGI_FORMAT_R32G32B32_FLOAT,0,0,D3D11_INPUT_PER_VERTEX_DATA,0},{"COLOR",0,DXGI_FORMAT_R8G8B8A8_UNORM,0,12,D3D11_INPUT_PER_VERTEX_DATA,0}};
    hr=device_->CreateInputLayout(cl,2,vs->GetBufferPointer(),vs->GetBufferSize(),colorLayout_.GetAddressOf());if(FAILED(hr))return Fail(L"CreateInputLayout (color)",hr);
    D3D11_BUFFER_DESC cb{};cb.ByteWidth=sizeof(FrameConstants);cb.Usage=D3D11_USAGE_DEFAULT;cb.BindFlags=D3D11_BIND_CONSTANT_BUFFER;hr=device_->CreateBuffer(&cb,nullptr,frameCb_.GetAddressOf());return SUCCEEDED(hr)||Fail(L"CreateBuffer (frame constants)",hr);
}
bool Renderer::CreateTextureArray(const TexturePack& textures,WarningLog& warnings){
    ComPtr<IWICImagingFactory> factory;HRESULT hr=CoCreateInstance(CLSID_WICImagingFactory2,nullptr,CLSCTX_INPROC_SERVER,IID_PPV_ARGS(factory.GetAddressOf()));if(FAILED(hr))hr=CoCreateInstance(CLSID_WICImagingFactory,nullptr,CLSCTX_INPROC_SERVER,IID_PPV_ARGS(factory.GetAddressOf()));if(FAILED(hr))return Fail(L"Create WIC imaging factory",hr);
    const auto& paths=textures.SlicePaths();if(paths.empty())return Fail(L"Create texture array",E_FAIL,L"No texture slices were discovered.");if(paths.size()>D3D11_REQ_TEXTURE2D_ARRAY_AXIS_DIMENSION)return Fail(L"Create texture array",E_INVALIDARG,L"The texture pack contains more texture slices than Direct3D 11 permits.");
    std::vector<std::vector<uint8_t>> images(paths.size());auto checker=Checker();
    for(size_t i=0;i<paths.size();++i)if(!DecodePng(factory.Get(),paths[i],images[i])){images[i]=checker;if(!paths[i].empty())warnings.Add(paths[i].filename().wstring()+L" (unreadable PNG)");}
    D3D11_TEXTURE2D_DESC td{};td.Width=16;td.Height=16;td.MipLevels=1;td.ArraySize=static_cast<UINT>(images.size());td.Format=DXGI_FORMAT_R8G8B8A8_UNORM_SRGB;td.SampleDesc.Count=1;td.Usage=D3D11_USAGE_IMMUTABLE;td.BindFlags=D3D11_BIND_SHADER_RESOURCE;
    std::vector<D3D11_SUBRESOURCE_DATA> init(images.size());for(size_t i=0;i<images.size();++i){init[i].pSysMem=images[i].data();init[i].SysMemPitch=64;}
    ComPtr<ID3D11Texture2D> tex;hr=device_->CreateTexture2D(&td,init.data(),tex.GetAddressOf());if(FAILED(hr)){td.Format=DXGI_FORMAT_R8G8B8A8_UNORM;hr=device_->CreateTexture2D(&td,init.data(),tex.GetAddressOf());}if(FAILED(hr))return Fail(L"CreateTexture2D (block texture array)",hr);
    D3D11_SHADER_RESOURCE_VIEW_DESC sv{};sv.Format=td.Format;sv.ViewDimension=D3D11_SRV_DIMENSION_TEXTURE2DARRAY;sv.Texture2DArray.MipLevels=1;sv.Texture2DArray.ArraySize=td.ArraySize;
    hr=device_->CreateShaderResourceView(tex.Get(),&sv,textureArray_.GetAddressOf());return SUCCEEDED(hr)||Fail(L"CreateShaderResourceView (block texture array)",hr);
}
bool Renderer::CreateStates(){
    D3D11_SAMPLER_DESC sd{};sd.Filter=D3D11_FILTER_MIN_MAG_MIP_POINT;sd.AddressU=sd.AddressV=sd.AddressW=D3D11_TEXTURE_ADDRESS_WRAP;sd.MaxAnisotropy=1;sd.ComparisonFunc=D3D11_COMPARISON_NEVER;sd.MinLOD=0.0f;sd.MaxLOD=D3D11_FLOAT32_MAX;HRESULT hr=device_->CreateSamplerState(&sd,sampler_.GetAddressOf());if(FAILED(hr))return Fail(L"CreateSamplerState",hr);
    D3D11_RASTERIZER_DESC rd{};rd.FillMode=D3D11_FILL_SOLID;rd.CullMode=D3D11_CULL_BACK;rd.FrontCounterClockwise=FALSE;rd.DepthClipEnable=TRUE;hr=device_->CreateRasterizerState(&rd,solidRaster_.GetAddressOf());if(FAILED(hr))return Fail(L"CreateRasterizerState (solid)",hr);
    rd.CullMode=D3D11_CULL_NONE;rd.DepthBias=-2;rd.SlopeScaledDepthBias=-1.0f;hr=device_->CreateRasterizerState(&rd,overlayRaster_.GetAddressOf());if(FAILED(hr))return Fail(L"CreateRasterizerState (overlay)",hr);rd.DepthBias=-4;hr=device_->CreateRasterizerState(&rd,lineRaster_.GetAddressOf());if(FAILED(hr))return Fail(L"CreateRasterizerState (outline)",hr);
    D3D11_BLEND_DESC bd{};bd.RenderTarget[0].BlendEnable=TRUE;bd.RenderTarget[0].SrcBlend=D3D11_BLEND_SRC_ALPHA;bd.RenderTarget[0].DestBlend=D3D11_BLEND_INV_SRC_ALPHA;bd.RenderTarget[0].BlendOp=D3D11_BLEND_OP_ADD;bd.RenderTarget[0].SrcBlendAlpha=D3D11_BLEND_ONE;bd.RenderTarget[0].DestBlendAlpha=D3D11_BLEND_ZERO;bd.RenderTarget[0].BlendOpAlpha=D3D11_BLEND_OP_ADD;bd.RenderTarget[0].RenderTargetWriteMask=D3D11_COLOR_WRITE_ENABLE_ALL;hr=device_->CreateBlendState(&bd,alphaBlend_.GetAddressOf());if(FAILED(hr))return Fail(L"CreateBlendState",hr);
    D3D11_DEPTH_STENCIL_DESC dsd{};dsd.DepthEnable=TRUE;dsd.DepthWriteMask=D3D11_DEPTH_WRITE_MASK_ALL;dsd.DepthFunc=D3D11_COMPARISON_LESS_EQUAL;hr=device_->CreateDepthStencilState(&dsd,depthState_.GetAddressOf());if(FAILED(hr))return Fail(L"CreateDepthStencilState",hr);
    D3D11_BUFFER_DESC vb{};vb.ByteWidth=sizeof(VoxelVertex)*24;vb.Usage=D3D11_USAGE_DYNAMIC;vb.BindFlags=D3D11_BIND_VERTEX_BUFFER;vb.CPUAccessFlags=D3D11_CPU_ACCESS_WRITE;hr=device_->CreateBuffer(&vb,nullptr,overlayVb_.GetAddressOf());if(FAILED(hr))return Fail(L"CreateBuffer (crack vertices)",hr);
    D3D11_BUFFER_DESC ib{};ib.ByteWidth=sizeof(uint32_t)*36;ib.Usage=D3D11_USAGE_DYNAMIC;ib.BindFlags=D3D11_BIND_INDEX_BUFFER;ib.CPUAccessFlags=D3D11_CPU_ACCESS_WRITE;hr=device_->CreateBuffer(&ib,nullptr,overlayIb_.GetAddressOf());if(FAILED(hr))return Fail(L"CreateBuffer (crack indices)",hr);
    vb.ByteWidth=sizeof(ColorVertex)*24;hr=device_->CreateBuffer(&vb,nullptr,lineVb_.GetAddressOf());return SUCCEEDED(hr)||Fail(L"CreateBuffer (outline vertices)",hr);
}

}
