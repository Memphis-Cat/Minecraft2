#include "Player.h"
#include <numbers>

namespace mc {
namespace {
bool Intersects(const Aabb&a,const Aabb&b){return a.max.x>b.min.x&&a.min.x<b.max.x&&a.max.y>b.min.y&&a.min.y<b.max.y&&a.max.z>b.min.z&&a.min.z<b.max.z;}
}
Player::Player()=default;
Aabb Player::BoxAt(const Vec3& p) const{return {{p.x-0.3,p.y,p.z-0.3},{p.x+0.3,p.y+1.8,p.z+0.3}};}
bool Player::Collides(const Aabb& box,const World& world) const{
    constexpr double e=1e-7;
    for(int y=FloorToInt(box.min.y+e);y<=FloorToInt(box.max.y-e);++y)
        for(int z=FloorToInt(box.min.z+e);z<=FloorToInt(box.max.z-e);++z)
            for(int x=FloorToInt(box.min.x+e);x<=FloorToInt(box.max.x-e);++x)
                if(world.IsSolid(x,y,z)&&Intersects(box,{{double(x),double(y),double(z)},{double(x+1),double(y+1),double(z+1)}}))return true;
    return false;
}
bool Player::SupportedAt(const Vec3& p,const World& world) const{Aabb b=BoxAt(p);b.min.y-=0.06;b.max.y=b.min.y+0.05;return Collides(b,world);}
double Player::ClipAxis(const Aabb& box,double amount,int axis,const World& world) const{
    if(std::abs(amount)<1e-12)return 0.0; Aabb swept=box;
    if(axis==0){if(amount>0)swept.max.x+=amount;else swept.min.x+=amount;}
    if(axis==1){if(amount>0)swept.max.y+=amount;else swept.min.y+=amount;}
    if(axis==2){if(amount>0)swept.max.z+=amount;else swept.min.z+=amount;}
    constexpr double e=1e-7;
    for(int y=FloorToInt(swept.min.y+e);y<=FloorToInt(swept.max.y-e);++y)
        for(int z=FloorToInt(swept.min.z+e);z<=FloorToInt(swept.max.z-e);++z)
            for(int x=FloorToInt(swept.min.x+e);x<=FloorToInt(swept.max.x-e);++x){
                if(!world.IsSolid(x,y,z))continue; Aabb block{{double(x),double(y),double(z)},{double(x+1),double(y+1),double(z+1)}};
                if(axis==0&&box.max.y>block.min.y&&box.min.y<block.max.y&&box.max.z>block.min.z&&box.min.z<block.max.z){if(amount>0&&box.max.x<=block.min.x)amount=std::min(amount,block.min.x-box.max.x);else if(amount<0&&box.min.x>=block.max.x)amount=std::max(amount,block.max.x-box.min.x);}
                if(axis==1&&box.max.x>block.min.x&&box.min.x<block.max.x&&box.max.z>block.min.z&&box.min.z<block.max.z){if(amount>0&&box.max.y<=block.min.y)amount=std::min(amount,block.min.y-box.max.y);else if(amount<0&&box.min.y>=block.max.y)amount=std::max(amount,block.max.y-box.min.y);}
                if(axis==2&&box.max.x>block.min.x&&box.min.x<block.max.x&&box.max.y>block.min.y&&box.min.y<block.max.y){if(amount>0&&box.max.z<=block.min.z)amount=std::min(amount,block.min.z-box.max.z);else if(amount<0&&box.min.z>=block.max.z)amount=std::max(amount,block.max.z-box.min.z);}
            }
    return amount;
}
void Player::Move(Vec3 delta,const World& world){
    if(sneaking_&&onGround_){
        while(std::abs(delta.x)>0.0&&!SupportedAt({position_.x+delta.x,position_.y,position_.z},world)){if(std::abs(delta.x)<=0.05){delta.x=0;break;}delta.x+=delta.x>0?-0.05:0.05;}
        while(std::abs(delta.z)>0.0&&!SupportedAt({position_.x,position_.y,position_.z+delta.z},world)){if(std::abs(delta.z)<=0.05){delta.z=0;break;}delta.z+=delta.z>0?-0.05:0.05;}
    }
    const double requestedX=delta.x,requestedY=delta.y,requestedZ=delta.z;
    Aabb box=BoxAt(position_); delta.y=ClipAxis(box,delta.y,1,world);position_.y+=delta.y;box=BoxAt(position_);
    delta.x=ClipAxis(box,delta.x,0,world);position_.x+=delta.x;box=BoxAt(position_);
    delta.z=ClipAxis(box,delta.z,2,world);position_.z+=delta.z;
    onGround_=requestedY<0&&requestedY!=delta.y; horizontalCollision_=requestedX!=delta.x||requestedZ!=delta.z;
    if(requestedX!=delta.x)velocity_.x=0;if(requestedY!=delta.y)velocity_.y=0;if(requestedZ!=delta.z)velocity_.z=0;
    walked_+=std::sqrt(delta.x*delta.x+delta.z*delta.z); cameraBob_=onGround_?std::sin(walked_*10.0)*std::min(0.06,std::sqrt(delta.x*delta.x+delta.z*delta.z)*0.45):cameraBob_*0.8;
}
void Player::Tick(const InputState& input,const World& world){
    sneaking_=input.sneak;
    if(sprintTapTicks_>0)--sprintTapTicks_;
    bool forwardPressed=input.forward&&!wasForward_; wasForward_=input.forward;
    if(!sneaking_&&input.forward&&(input.sprint||(forwardPressed&&sprintTapTicks_>0)))sprinting_=true;
    if(forwardPressed&&sprintTapTicks_==0)sprintTapTicks_=7;
    if(sneaking_||!input.forward||horizontalCollision_)sprinting_=false;

    double forward=(input.forward?1.0:0.0)-(input.back?1.0:0.0);
    double strafe=(input.left?1.0:0.0)-(input.right?1.0:0.0);
    if(sneaking_){forward*=0.3;strafe*=0.3;}
    double mag=std::sqrt(forward*forward+strafe*strafe);if(mag>1){forward/=mag;strafe/=mag;}
    double friction=onGround_?0.54600006:0.91;
    double acceleration=onGround_?0.1*(0.16277136/(friction*friction*friction)):0.02;
    if(sprinting_)acceleration*=1.3;
    double yawRad=yaw_*std::numbers::pi/180.0,s=std::sin(yawRad),c=std::cos(yawRad);
    velocity_.x+=(strafe*c-forward*s)*acceleration; velocity_.z+=(forward*c+strafe*s)*acceleration;
    if(input.jump&&onGround_){velocity_.y=0.42;if(sprinting_){velocity_.x-=s*0.2;velocity_.z+=c*0.2;}}
    Move(velocity_,world);
    velocity_.y-=0.08;velocity_.y*=0.98;velocity_.x*=friction;velocity_.z*=friction;
}
void Player::ApplyMouseDelta(double dx,double dy,double sensitivity){double f=sensitivity*0.6+0.2,scale=f*f*f*8.0*0.15;yaw_=std::fmod(yaw_+float(dx*scale),360.0f);pitch_=std::clamp(pitch_+float(dy*scale),-90.0f,90.0f);}
Vec3 Player::LookDirection() const{double yr=yaw_*std::numbers::pi/180.0,pr=pitch_*std::numbers::pi/180.0,cp=std::cos(pr);return Normalize({-std::sin(yr)*cp,-std::sin(pr),std::cos(yr)*cp});}
}
