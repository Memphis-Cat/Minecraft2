#include "Renderer.h"
#include <cstring>
#include <numbers>

using namespace DirectX;

namespace mc {
namespace {
Vec3 Cross(const Vec3& a,const Vec3& b){return {a.y*b.z-a.z*b.y,a.z*b.x-a.x*b.z,a.x*b.y-a.y*b.x};}
uint32_t PackRgba(float r,float g,float b,float a){
    const auto channel=[](float value){return static_cast<uint32_t>(std::clamp(value,0.0f,1.0f)*255.0f+0.5f);};
    return channel(r)|(channel(g)<<8)|(channel(b)<<16)|(channel(a)<<24);
}
}

void Renderer::UploadChunk(ChunkCoord coord,const MeshData& mesh){
    if(mesh.indices.empty()){chunks_.erase(coord);return;}
    GpuChunk gpu;gpu.indexCount=static_cast<uint32_t>(mesh.indices.size());gpu.revision=mesh.revision;
    D3D11_BUFFER_DESC vertexDescription{};vertexDescription.ByteWidth=static_cast<UINT>(mesh.vertices.size()*sizeof(VoxelVertex));vertexDescription.Usage=D3D11_USAGE_IMMUTABLE;vertexDescription.BindFlags=D3D11_BIND_VERTEX_BUFFER;
    D3D11_SUBRESOURCE_DATA vertexData{mesh.vertices.data(),0,0};if(FAILED(device_->CreateBuffer(&vertexDescription,&vertexData,&gpu.vb)))return;
    D3D11_BUFFER_DESC indexDescription{};indexDescription.ByteWidth=static_cast<UINT>(mesh.indices.size()*sizeof(uint32_t));indexDescription.Usage=D3D11_USAGE_IMMUTABLE;indexDescription.BindFlags=D3D11_BIND_INDEX_BUFFER;
    D3D11_SUBRESOURCE_DATA indexData{mesh.indices.data(),0,0};if(FAILED(device_->CreateBuffer(&indexDescription,&indexData,&gpu.ib)))return;
    chunks_[coord]=std::move(gpu);
}

void Renderer::RemoveChunk(ChunkCoord coord){chunks_.erase(coord);}

void Renderer::UpdateFrameConstants(const Player& player,double interpolationAlpha,uint64_t worldTime){
    Vec3 eyePosition=player.InterpolatedEyePosition(interpolationAlpha);
    const Vec3 direction=player.LookDirection();
    eyePosition.y+=player.InterpolatedCameraBob(interpolationAlpha);
    const XMVECTOR eye=XMVectorSet(float(eyePosition.x),float(eyePosition.y),float(eyePosition.z),1);
    const XMVECTOR target=XMVectorSet(float(eyePosition.x+direction.x),float(eyePosition.y+direction.y),float(eyePosition.z+direction.z),1);
    const XMVECTOR up=XMVectorSet(0,1,0,0);
    const XMMATRIX viewProjection=XMMatrixLookAtLH(eye,target,up)*XMMatrixPerspectiveFovLH(XMConvertToRadians(70.0f),float(width_)/float(height_),0.05f,512.0f);
    XMStoreFloat4x4(&currentViewProjection_,viewProjection);

    const double dayTicks=std::fmod(double(worldTime)+interpolationAlpha,24000.0);
    const double angle=dayTicks*(2.0*std::numbers::pi/24000.0);
    const double sunHeight=std::sin(angle);
    daylight_=float(std::clamp((sunHeight+0.15)/0.35,0.0,1.0));
    skyDarken_=std::round((1.0f-daylight_)*11.0f);

    const FrameConstants constants{currentViewProjection_,skyDarken_,daylight_,{0.0f,0.0f}};
    context_->UpdateSubresource(frameCb_.Get(),0,nullptr,&constants,0,0);
}

void Renderer::DrawEnvironment(const Player& player,double interpolationAlpha,uint64_t worldTime){
    if(!sunTexture_&&!cloudsTexture_)return;

    UINT stride=sizeof(SpriteVertex),offset=0;
    context_->IASetInputLayout(spriteLayout_.Get());
    context_->IASetVertexBuffers(0,1,environmentVb_.GetAddressOf(),&stride,&offset);
    context_->IASetPrimitiveTopology(D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST);
    context_->VSSetShader(spriteVs_.Get(),nullptr,0);
    context_->PSSetShader(spritePs_.Get(),nullptr,0);
    context_->PSSetSamplers(0,1,sampler_.GetAddressOf());
    context_->RSSetState(overlayRaster_.Get());
    context_->OMSetDepthStencilState(skyDepthState_.Get(),0);
    float blendFactor[4]={};
    context_->OMSetBlendState(alphaBlend_.Get(),blendFactor,0xffffffff);

    const auto draw=[&](ID3D11ShaderResourceView* texture,const std::array<SpriteVertex,6>& vertices){
        if(!texture)return;
        D3D11_MAPPED_SUBRESOURCE mapped{};
        if(FAILED(context_->Map(environmentVb_.Get(),0,D3D11_MAP_WRITE_DISCARD,0,&mapped)))return;
        std::memcpy(mapped.pData,vertices.data(),sizeof(vertices));
        context_->Unmap(environmentVb_.Get(),0);
        context_->PSSetShaderResources(0,1,&texture);
        context_->Draw(6,0);
    };

    const double preciseTicks=double(worldTime)+interpolationAlpha;
    const double dayTicks=std::fmod(preciseTicks,24000.0);
    const double angle=dayTicks*(2.0*std::numbers::pi/24000.0);

    if(sunTexture_){
        const Vec3 sunDirection=Normalize({std::cos(angle),std::sin(angle),0.15});
        if(sunDirection.y>-0.08){
            const Vec3 eye=player.InterpolatedEyePosition(interpolationAlpha);
            const Vec3 center=eye+sunDirection*180.0;
            Vec3 right=Normalize(Cross({0,1,0},sunDirection));
            if(Length(right)<1e-6)right={1,0,0};
            const Vec3 up=Normalize(Cross(sunDirection,right));
            const double halfSize=18.0;
            const Vec3 p0=center-right*halfSize+up*halfSize;
            const Vec3 p1=center+right*halfSize+up*halfSize;
            const Vec3 p2=center+right*halfSize-up*halfSize;
            const Vec3 p3=center-right*halfSize-up*halfSize;
            const uint32_t white=0xFFFFFFFFu;
            draw(sunTexture_.Get(),{{{float(p0.x),float(p0.y),float(p0.z),0,0,white},{float(p1.x),float(p1.y),float(p1.z),1,0,white},{float(p2.x),float(p2.y),float(p2.z),1,1,white},{float(p0.x),float(p0.y),float(p0.z),0,0,white},{float(p2.x),float(p2.y),float(p2.z),1,1,white},{float(p3.x),float(p3.y),float(p3.z),0,1,white}}});
        }
    }

    if(cloudsTexture_){
        const Vec3 eye=player.InterpolatedEyePosition(interpolationAlpha);
        const float centerX=float(std::floor(eye.x/16.0)*16.0);
        const float centerZ=float(std::floor(eye.z/16.0)*16.0);
        const float y=128.0f,halfSize=256.0f;
        const float scroll=float(preciseTicks*0.03);
        const float u0=(centerX-halfSize+scroll)/256.0f,u1=(centerX+halfSize+scroll)/256.0f;
        const float v0=(centerZ-halfSize)/256.0f,v1=(centerZ+halfSize)/256.0f;
        const float cloudBrightness=0.25f+0.75f*daylight_;
        const uint32_t cloudColor=PackRgba(cloudBrightness,cloudBrightness,cloudBrightness,0.78f);
        draw(cloudsTexture_.Get(),{{{centerX-halfSize,y,centerZ-halfSize,u0,v0,cloudColor},{centerX+halfSize,y,centerZ-halfSize,u1,v0,cloudColor},{centerX+halfSize,y,centerZ+halfSize,u1,v1,cloudColor},{centerX-halfSize,y,centerZ-halfSize,u0,v0,cloudColor},{centerX+halfSize,y,centerZ+halfSize,u1,v1,cloudColor},{centerX-halfSize,y,centerZ+halfSize,u0,v1,cloudColor}}});
    }

    context_->OMSetBlendState(nullptr,nullptr,0xffffffff);
}

void Renderer::DrawCrosshair(){
    if(!iconsTexture_||iconsWidth_<15||iconsHeight_<15)return;
    const float halfWidth=15.0f/float(width_),halfHeight=15.0f/float(height_);
    const float u0=0.5f/float(iconsWidth_),v0=0.5f/float(iconsHeight_),u1=14.5f/float(iconsWidth_),v1=14.5f/float(iconsHeight_);
    const std::array<UiVertex,6> vertices={{{-halfWidth,halfHeight,u0,v0},{halfWidth,halfHeight,u1,v0},{halfWidth,-halfHeight,u1,v1},{-halfWidth,halfHeight,u0,v0},{halfWidth,-halfHeight,u1,v1},{-halfWidth,-halfHeight,u0,v1}}};
    D3D11_MAPPED_SUBRESOURCE mapped{};if(FAILED(context_->Map(uiVb_.Get(),0,D3D11_MAP_WRITE_DISCARD,0,&mapped)))return;std::memcpy(mapped.pData,vertices.data(),sizeof(vertices));context_->Unmap(uiVb_.Get(),0);
    UINT stride=sizeof(UiVertex),offset=0;context_->IASetInputLayout(uiLayout_.Get());context_->IASetVertexBuffers(0,1,uiVb_.GetAddressOf(),&stride,&offset);context_->IASetPrimitiveTopology(D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST);context_->VSSetShader(uiVs_.Get(),nullptr,0);context_->PSSetShader(uiPs_.Get(),nullptr,0);context_->PSSetShaderResources(0,1,iconsTexture_.GetAddressOf());context_->PSSetSamplers(0,1,sampler_.GetAddressOf());context_->OMSetDepthStencilState(skyDepthState_.Get(),0);context_->RSSetState(overlayRaster_.Get());float blendFactor[4]={};context_->OMSetBlendState(invertBlend_.Get(),blendFactor,0xffffffff);context_->Draw(6,0);context_->OMSetBlendState(nullptr,nullptr,0xffffffff);
}

void Renderer::DrawCracks(const RayHit& hit,uint16_t slice){
    const float epsilon=0.002f,x=float(hit.block.x)-epsilon,y=float(hit.block.y)-epsilon,z=float(hit.block.z)-epsilon,size=1+2*epsilon;
    std::vector<VoxelVertex> vertices;std::vector<uint32_t> indices;vertices.reserve(24);indices.reserve(36);
    const auto face=[&](std::array<XMFLOAT3,4> points){const uint32_t base=uint32_t(vertices.size());for(int i=0;i<4;++i)vertices.push_back({points[i].x,points[i].y,points[i].z,float((i==1||i==2)?1:0),float(i>=2?1:0),slice,0xF0FFFFFFu});indices.insert(indices.end(),{base,base+1,base+2,base,base+2,base+3});};
    face({{{x,y,z},{x,y+size,z},{x,y+size,z+size},{x,y,z+size}}});face({{{x+size,y,z+size},{x+size,y+size,z+size},{x+size,y+size,z},{x+size,y,z}}});face({{{x,y,z+size},{x+size,y,z+size},{x+size,y,z},{x,y,z}}});face({{{x,y+size,z},{x+size,y+size,z},{x+size,y+size,z+size},{x,y+size,z+size}}});face({{{x+size,y,z},{x+size,y+size,z},{x,y+size,z},{x,y,z}}});face({{{x,y,z+size},{x,y+size,z+size},{x+size,y+size,z+size},{x+size,y,z+size}}});
    D3D11_MAPPED_SUBRESOURCE mapped{};if(SUCCEEDED(context_->Map(overlayVb_.Get(),0,D3D11_MAP_WRITE_DISCARD,0,&mapped))){std::memcpy(mapped.pData,vertices.data(),vertices.size()*sizeof(VoxelVertex));context_->Unmap(overlayVb_.Get(),0);}if(SUCCEEDED(context_->Map(overlayIb_.Get(),0,D3D11_MAP_WRITE_DISCARD,0,&mapped))){std::memcpy(mapped.pData,indices.data(),indices.size()*sizeof(uint32_t));context_->Unmap(overlayIb_.Get(),0);}
    UINT stride=sizeof(VoxelVertex),offset=0;context_->IASetInputLayout(voxelLayout_.Get());context_->IASetVertexBuffers(0,1,overlayVb_.GetAddressOf(),&stride,&offset);context_->IASetIndexBuffer(overlayIb_.Get(),DXGI_FORMAT_R32_UINT,0);context_->IASetPrimitiveTopology(D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST);context_->RSSetState(overlayRaster_.Get());float blend[4]={};context_->OMSetBlendState(alphaBlend_.Get(),blend,0xffffffff);context_->DrawIndexed(36,0,0);context_->OMSetBlendState(nullptr,nullptr,0xffffffff);
}

void Renderer::DrawOutline(const RayHit& hit){
    std::array<ColorVertex,72> vertices{};size_t output=0;const int edges[24]={0,1,1,2,2,3,3,0,4,5,5,6,6,7,7,4,0,4,1,5,2,6,3,7};
    for(const float epsilon:{0.004f,0.009f,0.014f}){const float x=float(hit.block.x)-epsilon,y=float(hit.block.y)-epsilon,z=float(hit.block.z)-epsilon,size=1+2*epsilon;const XMFLOAT3 points[8]={{x,y,z},{x+size,y,z},{x+size,y,z+size},{x,y,z+size},{x,y+size,z},{x+size,y+size,z},{x+size,y+size,z+size},{x,y+size,z+size}};for(int edge=0;edge<24;++edge){const auto& point=points[edges[edge]];vertices[output++]={point.x,point.y,point.z,0xFF050505};}}
    D3D11_MAPPED_SUBRESOURCE mapped{};if(SUCCEEDED(context_->Map(lineVb_.Get(),0,D3D11_MAP_WRITE_DISCARD,0,&mapped))){std::memcpy(mapped.pData,vertices.data(),sizeof(vertices));context_->Unmap(lineVb_.Get(),0);}UINT stride=sizeof(ColorVertex),offset=0;context_->IASetInputLayout(colorLayout_.Get());context_->IASetVertexBuffers(0,1,lineVb_.GetAddressOf(),&stride,&offset);context_->IASetPrimitiveTopology(D3D11_PRIMITIVE_TOPOLOGY_LINELIST);context_->VSSetShader(colorVs_.Get(),nullptr,0);context_->PSSetShader(colorPs_.Get(),nullptr,0);context_->RSSetState(lineRaster_.Get());context_->Draw(static_cast<UINT>(vertices.size()),0);
}

void Renderer::Render(const Player& player,const std::optional<RayHit>& target,int destroyStage,double interpolationAlpha,uint64_t worldTime){
    context_->OMSetRenderTargets(1,rtv_.GetAddressOf(),dsv_.Get());
    const D3D11_VIEWPORT viewport{0,0,float(width_),float(height_),0,1};context_->RSSetViewports(1,&viewport);
    UpdateFrameConstants(player,interpolationAlpha,worldTime);
    const float night[3]={0.008f,0.012f,0.035f},day[3]={0.47f,0.67f,1.0f};
    const float sky[4]={night[0]+(day[0]-night[0])*daylight_,night[1]+(day[1]-night[1])*daylight_,night[2]+(day[2]-night[2])*daylight_,1.0f};
    context_->ClearRenderTargetView(rtv_.Get(),sky);context_->ClearDepthStencilView(dsv_.Get(),D3D11_CLEAR_DEPTH|D3D11_CLEAR_STENCIL,1,0);
    ID3D11Buffer* constants=frameCb_.Get();context_->VSSetConstantBuffers(0,1,&constants);

    DrawEnvironment(player,interpolationAlpha,worldTime);

    context_->OMSetDepthStencilState(depthState_.Get(),0);context_->VSSetShader(voxelVs_.Get(),nullptr,0);context_->PSSetShader(voxelPs_.Get(),nullptr,0);context_->PSSetShaderResources(0,1,textureArray_.GetAddressOf());context_->PSSetSamplers(0,1,sampler_.GetAddressOf());context_->IASetInputLayout(voxelLayout_.Get());context_->IASetPrimitiveTopology(D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST);context_->RSSetState(solidRaster_.Get());
    // The streamed chunk set is already distance-limited. Drawing it directly avoids the old angle-dependent frustum bug.
    for(const auto& [coord,gpu]:chunks_){(void)coord;UINT stride=sizeof(VoxelVertex),offset=0;ID3D11Buffer* vertexBuffer=gpu.vb.Get();context_->IASetVertexBuffers(0,1,&vertexBuffer,&stride,&offset);context_->IASetIndexBuffer(gpu.ib.Get(),DXGI_FORMAT_R32_UINT,0);context_->DrawIndexed(gpu.indexCount,0,0);}

    if(target){context_->VSSetShader(voxelVs_.Get(),nullptr,0);context_->PSSetShader(voxelPs_.Get(),nullptr,0);context_->PSSetShaderResources(0,1,textureArray_.GetAddressOf());if(destroyStage>=0&&destroyStage<10)DrawCracks(*target,destroyStages_[destroyStage]);DrawOutline(*target);}
    DrawFirstPersonArm();
    DrawCrosshair();
    swapChain_->Present(1,0);
}
}
