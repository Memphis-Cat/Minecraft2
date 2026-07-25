#include "Renderer.h"
#include <cstring>

using namespace DirectX;
namespace mc {
void Renderer::UploadChunk(ChunkCoord coord,const MeshData& mesh){
    if(mesh.indices.empty()){chunks_.erase(coord);return;}GpuChunk gpu;gpu.indexCount=static_cast<uint32_t>(mesh.indices.size());gpu.revision=mesh.revision;
    D3D11_BUFFER_DESC vd{};vd.ByteWidth=static_cast<UINT>(mesh.vertices.size()*sizeof(VoxelVertex));vd.Usage=D3D11_USAGE_IMMUTABLE;vd.BindFlags=D3D11_BIND_VERTEX_BUFFER;D3D11_SUBRESOURCE_DATA vi{mesh.vertices.data(),0,0};if(FAILED(device_->CreateBuffer(&vd,&vi,&gpu.vb)))return;
    D3D11_BUFFER_DESC id{};id.ByteWidth=static_cast<UINT>(mesh.indices.size()*sizeof(uint32_t));id.Usage=D3D11_USAGE_IMMUTABLE;id.BindFlags=D3D11_BIND_INDEX_BUFFER;D3D11_SUBRESOURCE_DATA ii{mesh.indices.data(),0,0};if(FAILED(device_->CreateBuffer(&id,&ii,&gpu.ib)))return;chunks_[coord]=std::move(gpu);
}
void Renderer::RemoveChunk(ChunkCoord coord){chunks_.erase(coord);}
void Renderer::UpdateFrameConstants(const Player& player){
    Vec3 e=player.EyePosition(),d=player.LookDirection();e.y+=player.CameraBob();XMVECTOR eye=XMVectorSet(float(e.x),float(e.y),float(e.z),1),at=XMVectorSet(float(e.x+d.x),float(e.y+d.y),float(e.z+d.z),1),up=XMVectorSet(0,1,0,0);
    XMMATRIX vp=XMMatrixLookAtLH(eye,at,up)*XMMatrixPerspectiveFovLH(XMConvertToRadians(70.0f),float(width_)/float(height_),0.05f,512.0f);XMStoreFloat4x4(&currentViewProjection_,vp);FrameConstants cb{currentViewProjection_};context_->UpdateSubresource(frameCb_.Get(),0,nullptr,&cb,0,0);
}
std::array<Renderer::Plane,6> Renderer::ExtractFrustum(const XMFLOAT4X4&m)const{
    std::array<Plane,6> p={{{m._14+m._11,m._24+m._21,m._34+m._31,m._44+m._41},{m._14-m._11,m._24-m._21,m._34-m._31,m._44-m._41},{m._14+m._12,m._24+m._22,m._34+m._32,m._44+m._42},{m._14-m._12,m._24-m._22,m._34-m._32,m._44-m._42},{m._13,m._23,m._33,m._43},{m._14-m._13,m._24-m._23,m._34-m._33,m._44-m._43}}};
    for(auto&v:p){float l=std::sqrt(v.a*v.a+v.b*v.b+v.c*v.c);if(l>0){v.a/=l;v.b/=l;v.c/=l;v.d/=l;}}return p;
}
bool Renderer::Visible(ChunkCoord c,const std::array<Plane,6>& ps)const{float minx=float(c.x*kChunkSize),maxx=minx+kChunkSize,minz=float(c.z*kChunkSize),maxz=minz+kChunkSize;for(const auto&p:ps){float x=p.a>=0?maxx:minx,y=p.b>=0?float(kWorldHeight):0,z=p.c>=0?maxz:minz;if(p.a*x+p.b*y+p.c*z+p.d<0)return false;}return true;}
void Renderer::DrawCracks(const RayHit& hit,uint16_t slice){
    const float e=0.002f,x=float(hit.block.x)-e,y=float(hit.block.y)-e,z=float(hit.block.z)-e,s=1+2*e;std::vector<VoxelVertex> v;std::vector<uint32_t> idx;v.reserve(24);idx.reserve(36);
    auto face=[&](std::array<XMFLOAT3,4> p){uint32_t b=uint32_t(v.size());for(int i=0;i<4;++i)v.push_back({p[i].x,p[i].y,p[i].z,float((i==1||i==2)?1:0),float(i>=2?1:0),slice,0xFFFFFFFF});idx.insert(idx.end(),{b,b+1,b+2,b,b+2,b+3});};
    face({{{x,y,z},{x,y+s,z},{x,y+s,z+s},{x,y,z+s}}});face({{{x+s,y,z+s},{x+s,y+s,z+s},{x+s,y+s,z},{x+s,y,z}}});face({{{x,y,z+s},{x+s,y,z+s},{x+s,y,z},{x,y,z}}});face({{{x,y+s,z},{x+s,y+s,z},{x+s,y+s,z+s},{x,y+s,z+s}}});face({{{x+s,y,z},{x+s,y+s,z},{x,y+s,z},{x,y,z}}});face({{{x,y,z+s},{x,y+s,z+s},{x+s,y+s,z+s},{x+s,y,z+s}}});
    D3D11_MAPPED_SUBRESOURCE m{};if(SUCCEEDED(context_->Map(overlayVb_.Get(),0,D3D11_MAP_WRITE_DISCARD,0,&m))){memcpy(m.pData,v.data(),v.size()*sizeof(VoxelVertex));context_->Unmap(overlayVb_.Get(),0);}if(SUCCEEDED(context_->Map(overlayIb_.Get(),0,D3D11_MAP_WRITE_DISCARD,0,&m))){memcpy(m.pData,idx.data(),idx.size()*sizeof(uint32_t));context_->Unmap(overlayIb_.Get(),0);}
    UINT stride=sizeof(VoxelVertex),offset=0;context_->IASetInputLayout(voxelLayout_.Get());context_->IASetVertexBuffers(0,1,overlayVb_.GetAddressOf(),&stride,&offset);context_->IASetIndexBuffer(overlayIb_.Get(),DXGI_FORMAT_R32_UINT,0);context_->IASetPrimitiveTopology(D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST);context_->RSSetState(overlayRaster_.Get());float blend[4]={};context_->OMSetBlendState(alphaBlend_.Get(),blend,0xffffffff);context_->DrawIndexed(36,0,0);context_->OMSetBlendState(nullptr,nullptr,0xffffffff);
}
void Renderer::DrawOutline(const RayHit& hit){
    const float e=0.004f,x=float(hit.block.x)-e,y=float(hit.block.y)-e,z=float(hit.block.z)-e,s=1+2*e;XMFLOAT3 p[8]={{x,y,z},{x+s,y,z},{x+s,y,z+s},{x,y,z+s},{x,y+s,z},{x+s,y+s,z},{x+s,y+s,z+s},{x,y+s,z+s}};int edges[24]={0,1,1,2,2,3,3,0,4,5,5,6,6,7,7,4,0,4,1,5,2,6,3,7};ColorVertex v[24];for(int i=0;i<24;++i)v[i]={p[edges[i]].x,p[edges[i]].y,p[edges[i]].z,0xFF000000};D3D11_MAPPED_SUBRESOURCE m{};if(SUCCEEDED(context_->Map(lineVb_.Get(),0,D3D11_MAP_WRITE_DISCARD,0,&m))){memcpy(m.pData,v,sizeof(v));context_->Unmap(lineVb_.Get(),0);}UINT stride=sizeof(ColorVertex),offset=0;context_->IASetInputLayout(colorLayout_.Get());context_->IASetVertexBuffers(0,1,lineVb_.GetAddressOf(),&stride,&offset);context_->IASetPrimitiveTopology(D3D11_PRIMITIVE_TOPOLOGY_LINELIST);context_->VSSetShader(colorVs_.Get(),nullptr,0);context_->PSSetShader(colorPs_.Get(),nullptr,0);context_->RSSetState(lineRaster_.Get());context_->Draw(24,0);
}
void Renderer::Render(const Player& player,const std::optional<RayHit>& target,int destroyStage){
    float sky[4]={0.47f,0.67f,1.0f,1};context_->OMSetRenderTargets(1,rtv_.GetAddressOf(),dsv_.Get());context_->ClearRenderTargetView(rtv_.Get(),sky);context_->ClearDepthStencilView(dsv_.Get(),D3D11_CLEAR_DEPTH|D3D11_CLEAR_STENCIL,1,0);D3D11_VIEWPORT vp{0,0,float(width_),float(height_),0,1};context_->RSSetViewports(1,&vp);context_->OMSetDepthStencilState(depthState_.Get(),0);UpdateFrameConstants(player);
    ID3D11Buffer* cb=frameCb_.Get();context_->VSSetConstantBuffers(0,1,&cb);context_->VSSetShader(voxelVs_.Get(),nullptr,0);context_->PSSetShader(voxelPs_.Get(),nullptr,0);context_->PSSetShaderResources(0,1,textureArray_.GetAddressOf());context_->PSSetSamplers(0,1,sampler_.GetAddressOf());context_->IASetInputLayout(voxelLayout_.Get());context_->IASetPrimitiveTopology(D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST);context_->RSSetState(solidRaster_.Get());auto planes=ExtractFrustum(currentViewProjection_);
    for(const auto&[coord,gpu]:chunks_){if(!Visible(coord,planes))continue;UINT stride=sizeof(VoxelVertex),offset=0;ID3D11Buffer* vb=gpu.vb.Get();context_->IASetVertexBuffers(0,1,&vb,&stride,&offset);context_->IASetIndexBuffer(gpu.ib.Get(),DXGI_FORMAT_R32_UINT,0);context_->DrawIndexed(gpu.indexCount,0,0);}
    if(target){context_->VSSetShader(voxelVs_.Get(),nullptr,0);context_->PSSetShader(voxelPs_.Get(),nullptr,0);if(destroyStage>=0&&destroyStage<10)DrawCracks(*target,destroyStages_[destroyStage]);DrawOutline(*target);}
    swapChain_->Present(1,0);
}
}
