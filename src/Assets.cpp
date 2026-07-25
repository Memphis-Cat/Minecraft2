#include "Assets.h"
#include <Windows.h>
#include <fstream>
#include <sstream>

namespace mc {
void WarningLog::Add(std::wstring message){ if(unique_.insert(message).second) messages_.push_back(std::move(message)); }
std::wstring WarningLog::Format() const {
    std::wostringstream out;
    out << L"The texture pack has missing or unreadable files. Minecraft2 will use fallback.png for them:\n\n";
    for(const auto& m:messages_) out << L"• " << m << L'\n';
    return out.str();
}
std::wstring Widen(std::string_view text){
    if(text.empty()) return {};
    int n=MultiByteToWideChar(CP_UTF8,0,text.data(),static_cast<int>(text.size()),nullptr,0);
    std::wstring out(n,L'\0');
    MultiByteToWideChar(CP_UTF8,0,text.data(),static_cast<int>(text.size()),out.data(),n); return out;
}
std::string Narrow(std::wstring_view text){
    if(text.empty()) return {};
    int n=WideCharToMultiByte(CP_UTF8,0,text.data(),static_cast<int>(text.size()),nullptr,0,nullptr,nullptr);
    std::string out(n,'\0');
    WideCharToMultiByte(CP_UTF8,0,text.data(),static_cast<int>(text.size()),out.data(),n,nullptr,nullptr); return out;
}
std::string Trim(std::string v){ auto ws=[](unsigned char c){return std::isspace(c)!=0;}; while(!v.empty()&&ws(v.front()))v.erase(v.begin()); while(!v.empty()&&ws(v.back()))v.pop_back(); return v; }
std::string Lower(std::string v){ std::transform(v.begin(),v.end(),v.begin(),[](unsigned char c){return char(std::tolower(c));}); return v; }

std::filesystem::path FindAssetRoot(){
    std::array<std::filesystem::path,4> candidates={
        std::filesystem::current_path()/L"assets"/L"assets",
        std::filesystem::current_path()/L".."/L"assets"/L"assets",
        std::filesystem::path(L"assets")/L"assets",
        std::filesystem::path(L"..")/L"assets"/L"assets"};
    for(const auto& p:candidates) if(std::filesystem::exists(p)) return std::filesystem::weakly_canonical(p);
    return candidates.front();
}
std::map<std::string,std::string> ReadProperties(const std::filesystem::path& path){
    std::map<std::string,std::string> result; std::ifstream in(path); std::string line;
    while(std::getline(in,line)){
        line=Trim(line); if(line.empty()||line[0]=='#') continue;
        auto eq=line.find('='); if(eq==std::string::npos) continue;
        result[Lower(Trim(line.substr(0,eq)))]=Trim(line.substr(eq+1));
    } return result;
}

void TexturePack::Build(const std::filesystem::path& assetRoot,const std::vector<std::string>& requiredNames,WarningLog& warnings){
    slices_.clear(); slicePaths_.clear(); destroyStages_.fill(0);
    const auto dir=assetRoot/L"textures"/L"blocks";
    std::map<std::string,std::filesystem::path> found;
    if(std::filesystem::exists(dir)){
        for(const auto& e:std::filesystem::directory_iterator(dir)){
            if(!e.is_regular_file()||Lower(e.path().extension().string())!=".png") continue;
            found[Lower(e.path().stem().string())]=e.path();
        }
    }
    auto fallbackIt=found.find("fallback");
    if(fallbackIt==found.end()){
        slicePaths_.push_back({});
        warnings.Add(L"fallback.png (using an in-memory magenta/black checker)");
    } else slicePaths_.push_back(fallbackIt->second);
    slices_["fallback"]=0;

    for(const auto& [name,path]:found){
        if(name=="fallback") continue;
        if(slicePaths_.size()>=65535) break;
        slices_[name]=static_cast<uint16_t>(slicePaths_.size()); slicePaths_.push_back(path);
    }
    for(const auto& raw:requiredNames){
        const auto name=Lower(raw);
        if(!slices_.contains(name)){
            slices_[name]=0;
            warnings.Add(Widen(name+".png"));
        }
    }
    for(int i=0;i<10;++i){
        const std::string name="destroy_stage_"+std::to_string(i);
        auto it=slices_.find(name);
        if(it==slices_.end()){
            slices_[name]=0; destroyStages_[i]=0; warnings.Add(Widen(name+".png"));
        } else destroyStages_[i]=it->second;
    }
}
uint16_t TexturePack::Slice(std::string_view textureName) const { auto it=slices_.find(Lower(std::string(textureName))); return it==slices_.end()?0:it->second; }
}
