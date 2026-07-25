#include "Blocks.h"
#include <charconv>
#include <set>

namespace mc {
namespace {
bool ParseBool(const std::string& v,bool fallback){ auto s=Lower(Trim(v)); if(s=="true"||s=="1"||s=="yes")return true; if(s=="false"||s=="0"||s=="no")return false; return fallback; }
float ParseFloat(const std::string& v,float fallback){ try{return std::stof(v);}catch(...){return fallback;} }
int ParseInt(const std::string& v,int fallback){ try{return std::stoi(v);}catch(...){return fallback;} }
TintMode ParseTint(const std::string& v){ return Lower(Trim(v))=="grass"?TintMode::Grass:TintMode::None; }
constexpr size_t I(Face f){return static_cast<size_t>(f);} 
}

void BlockRegistry::Discover(const std::filesystem::path& root,WarningLog& warnings){
    blocks_.clear(); byName_.clear();
    BlockDefinition air; air.id=0; air.name="air"; air.solid=false; air.breakable=false; air.breakTicks=-1;
    blocks_.push_back(air); byName_["air"]=0;

    const auto textureDir=root/L"textures"/L"blocks";
    const auto metadataDir=root/L"blocks";
    std::set<std::string> names;
    std::unordered_map<std::string,std::set<std::string>> discovered;
    if(std::filesystem::exists(textureDir)){
        for(const auto& e:std::filesystem::directory_iterator(textureDir)){
            if(!e.is_regular_file()||Lower(e.path().extension().string())!=".png") continue;
            std::string stem=Lower(e.path().stem().string());
            if(stem=="fallback"||stem.rfind("destroy_stage_",0)==0) continue;
            std::string base=stem;
            for(const std::string suffix:{"_bottom","_north","_south","_east","_west","_side","_top"}){
                if(base.size()>suffix.size()&&base.ends_with(suffix)){ base.resize(base.size()-suffix.size()); break; }
            }
            names.insert(base); discovered[base].insert(stem);
        }
    }
    if(std::filesystem::exists(metadataDir)){
        for(const auto& e:std::filesystem::directory_iterator(metadataDir)){
            if(e.is_regular_file()&&Lower(e.path().extension().string())==".block") names.insert(Lower(e.path().stem().string()));
        }
    }
    if(names.empty()) warnings.Add(L"No block PNGs or .block definitions were detected");

    for(const auto& name:names){
        BlockDefinition b; b.id=static_cast<BlockId>(blocks_.size()); b.name=name;
        const auto has=[&](const std::string& n){ auto it=discovered.find(name); return it!=discovered.end()&&it->second.contains(n); };
        const std::string base=has(name)?name:"fallback";
        b.textureNames.fill(base);
        if(has(name+"_side")) for(Face f:{Face::West,Face::East,Face::North,Face::South}) b.textureNames[I(f)]=name+"_side";
        if(has(name+"_top")){ b.textureNames[I(Face::Up)]=name+"_top"; if(!has(name+"_bottom")) b.textureNames[I(Face::Down)]=name+"_top"; }
        if(has(name+"_bottom")) b.textureNames[I(Face::Down)]=name+"_bottom";
        if(has(name+"_west")) b.textureNames[I(Face::West)]=name+"_west";
        if(has(name+"_east")) b.textureNames[I(Face::East)]=name+"_east";
        if(has(name+"_north")) b.textureNames[I(Face::North)]=name+"_north";
        if(has(name+"_south")) b.textureNames[I(Face::South)]=name+"_south";

        const auto props=ReadProperties(metadataDir/Widen(name+".block"));
        auto get=[&](std::string_view k)->std::optional<std::string>{auto it=props.find(std::string(k)); return it==props.end()?std::nullopt:std::optional<std::string>(it->second);};
        if(auto v=get("hardness")) b.hardness=ParseFloat(*v,b.hardness);
        b.breakTicks=b.hardness<0?-1:std::max(1,static_cast<int>(std::lround(double(b.hardness)*30.0)));
        if(auto v=get("break_ticks")) b.breakTicks=ParseInt(*v,b.breakTicks);
        if(auto v=get("solid")) b.solid=ParseBool(*v,b.solid);
        b.breakable=b.hardness>=0&&b.breakTicks>0;
        if(auto v=get("breakable")) b.breakable=ParseBool(*v,b.breakable);

        if(auto v=get("texture")) b.textureNames.fill(Lower(*v));
        if(auto v=get("side")) for(Face f:{Face::West,Face::East,Face::North,Face::South}) b.textureNames[I(f)]=Lower(*v);
        if(auto v=get("top")) b.textureNames[I(Face::Up)]=Lower(*v);
        if(auto v=get("bottom")) b.textureNames[I(Face::Down)]=Lower(*v);
        if(auto v=get("west")) b.textureNames[I(Face::West)]=Lower(*v);
        if(auto v=get("east")) b.textureNames[I(Face::East)]=Lower(*v);
        if(auto v=get("north")) b.textureNames[I(Face::North)]=Lower(*v);
        if(auto v=get("south")) b.textureNames[I(Face::South)]=Lower(*v);
        if(auto v=get("tint")) b.tint.fill(ParseTint(*v));
        if(auto v=get("tint_side")) for(Face f:{Face::West,Face::East,Face::North,Face::South}) b.tint[I(f)]=ParseTint(*v);
        if(auto v=get("tint_top")) b.tint[I(Face::Up)]=ParseTint(*v);
        if(auto v=get("tint_bottom")) b.tint[I(Face::Down)]=ParseTint(*v);
        byName_[name]=b.id; blocks_.push_back(std::move(b));
    }
}

void BlockRegistry::ResolveTextures(const TexturePack& pack){ for(auto& b:blocks_) for(size_t i=0;i<6;++i) b.textureSlices[i]=pack.Slice(b.textureNames[i]); }
const BlockDefinition& BlockRegistry::Get(BlockId id) const { return id<blocks_.size()?blocks_[id]:blocks_[0]; }
BlockId BlockRegistry::Find(std::string_view name) const { auto it=byName_.find(Lower(std::string(name))); return it==byName_.end()?0:it->second; }
std::vector<std::string> BlockRegistry::RequiredTextureNames() const { std::vector<std::string> out; for(const auto& b:blocks_) for(const auto& t:b.textureNames) if(!t.empty()) out.push_back(t); return out; }
}
