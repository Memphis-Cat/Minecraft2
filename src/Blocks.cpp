#include "Blocks.h"
#include <charconv>
#include <set>

namespace mc {
namespace {
bool ParseBool(const std::string& v,bool fallback){auto s=Lower(Trim(v));if(s=="true"||s=="1"||s=="yes")return true;if(s=="false"||s=="0"||s=="no")return false;return fallback;}
float ParseFloat(const std::string& v,float fallback){try{return std::stof(v);}catch(...){return fallback;}}
int ParseInt(const std::string& v,int fallback){try{return std::stoi(v);}catch(...){return fallback;}}
TintMode ParseTint(const std::string& v){return Lower(Trim(v))=="grass"?TintMode::Grass:TintMode::None;}
constexpr size_t I(Face f){return static_cast<size_t>(f);}
std::wstring AbsoluteForMessage(const std::filesystem::path& path){std::error_code ec;auto absolute=std::filesystem::absolute(path,ec);return (ec?path:absolute.lexically_normal()).wstring();}
}

void BlockRegistry::Discover(const std::filesystem::path& root,WarningLog& warnings){
    blocks_.clear();byName_.clear();
    BlockDefinition air;air.id=0;air.name="air";air.solid=false;air.breakable=false;air.breakTicks=-1;
    blocks_.push_back(air);byName_["air"]=0;

    const auto textureDir=root/L"textures"/L"blocks";
    const auto metadataDir=root/L"blocks";
    std::set<std::string> names;
    std::unordered_map<std::string,std::set<std::string>> discovered;
    std::error_code ec;
    if(std::filesystem::is_directory(textureDir,ec)){
        for(const auto& entry:std::filesystem::directory_iterator(textureDir)){
            if(!entry.is_regular_file()||Lower(entry.path().extension().string())!=".png")continue;
            std::string stem=Lower(entry.path().stem().string());
            if(stem=="fallback"||stem.rfind("destroy_stage_",0)==0)continue;
            std::string base=stem;
            for(const std::string suffix:{"_bottom","_north","_south","_east","_west","_side","_top"}){
                if(base.size()>suffix.size()&&base.ends_with(suffix)){base.resize(base.size()-suffix.size());break;}
            }
            names.insert(base);discovered[base].insert(stem);
        }
    }else{
        warnings.Add(L"Missing block texture directory: "+AbsoluteForMessage(textureDir));
    }

    ec.clear();
    if(std::filesystem::is_directory(metadataDir,ec)){
        for(const auto& entry:std::filesystem::directory_iterator(metadataDir)){
            if(entry.is_regular_file()&&Lower(entry.path().extension().string())==".block")names.insert(Lower(entry.path().stem().string()));
        }
    }else{
        warnings.Add(L"Missing block definition directory: "+AbsoluteForMessage(metadataDir));
    }

    if(names.empty())warnings.Add(L"No blocks were discovered in: "+AbsoluteForMessage(textureDir)+L" or "+AbsoluteForMessage(metadataDir));

    for(const auto& name:names){
        BlockDefinition block;block.id=static_cast<BlockId>(blocks_.size());block.name=name;
        const auto has=[&](const std::string& texture){auto it=discovered.find(name);return it!=discovered.end()&&it->second.contains(texture);};
        const std::string base=has(name)?name:"fallback";
        block.textureNames.fill(base);
        if(has(name+"_side"))for(Face face:{Face::West,Face::East,Face::North,Face::South})block.textureNames[I(face)]=name+"_side";
        if(has(name+"_top")){block.textureNames[I(Face::Up)]=name+"_top";if(!has(name+"_bottom"))block.textureNames[I(Face::Down)]=name+"_top";}
        if(has(name+"_bottom"))block.textureNames[I(Face::Down)]=name+"_bottom";
        if(has(name+"_west"))block.textureNames[I(Face::West)]=name+"_west";
        if(has(name+"_east"))block.textureNames[I(Face::East)]=name+"_east";
        if(has(name+"_north"))block.textureNames[I(Face::North)]=name+"_north";
        if(has(name+"_south"))block.textureNames[I(Face::South)]=name+"_south";

        const auto definitionPath=metadataDir/Widen(name+".block");
        const auto properties=ReadProperties(definitionPath);
        auto get=[&](std::string_view key)->std::optional<std::string>{auto it=properties.find(std::string(key));return it==properties.end()?std::nullopt:std::optional<std::string>(it->second);};
        if(auto value=get("hardness"))block.hardness=ParseFloat(*value,block.hardness);
        block.breakTicks=block.hardness<0?-1:std::max(1,static_cast<int>(std::lround(double(block.hardness)*30.0)));
        if(auto value=get("break_ticks"))block.breakTicks=ParseInt(*value,block.breakTicks);
        if(auto value=get("solid"))block.solid=ParseBool(*value,block.solid);
        block.breakable=block.hardness>=0&&block.breakTicks>0;
        if(auto value=get("breakable"))block.breakable=ParseBool(*value,block.breakable);

        if(auto value=get("texture"))block.textureNames.fill(Lower(*value));
        if(auto value=get("side"))for(Face face:{Face::West,Face::East,Face::North,Face::South})block.textureNames[I(face)]=Lower(*value);
        if(auto value=get("top"))block.textureNames[I(Face::Up)]=Lower(*value);
        if(auto value=get("bottom"))block.textureNames[I(Face::Down)]=Lower(*value);
        if(auto value=get("west"))block.textureNames[I(Face::West)]=Lower(*value);
        if(auto value=get("east"))block.textureNames[I(Face::East)]=Lower(*value);
        if(auto value=get("north"))block.textureNames[I(Face::North)]=Lower(*value);
        if(auto value=get("south"))block.textureNames[I(Face::South)]=Lower(*value);
        if(auto value=get("tint"))block.tint.fill(ParseTint(*value));
        if(auto value=get("tint_side"))for(Face face:{Face::West,Face::East,Face::North,Face::South})block.tint[I(face)]=ParseTint(*value);
        if(auto value=get("tint_top"))block.tint[I(Face::Up)]=ParseTint(*value);
        if(auto value=get("tint_bottom"))block.tint[I(Face::Down)]=ParseTint(*value);
        byName_[name]=block.id;blocks_.push_back(std::move(block));
    }
}

void BlockRegistry::ResolveTextures(const TexturePack& pack){for(auto& block:blocks_)for(size_t i=0;i<6;++i)block.textureSlices[i]=pack.Slice(block.textureNames[i]);}
const BlockDefinition& BlockRegistry::Get(BlockId id) const {return id<blocks_.size()?blocks_[id]:blocks_[0];}
BlockId BlockRegistry::Find(std::string_view name) const {auto it=byName_.find(Lower(std::string(name)));return it==byName_.end()?0:it->second;}
std::vector<std::string> BlockRegistry::RequiredTextureNames() const {std::vector<std::string> out;for(const auto& block:blocks_)for(const auto& texture:block.textureNames)if(!texture.empty())out.push_back(texture);return out;}
}
