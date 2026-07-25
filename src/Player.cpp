#include "Player.h"
#include <numbers>

namespace mc {
namespace {
bool Intersects(const Aabb& a,const Aabb& b){return a.max.x>b.min.x&&a.min.x<b.max.x&&a.max.y>b.min.y&&a.min.y<b.max.y&&a.max.z>b.min.z&&a.min.z<b.max.z;}
}

Player::Player()=default;
Aabb Player::BoxAt(const Vec3& position) const{return {{position.x-0.3,position.y,position.z-0.3},{position.x+0.3,position.y+1.8,position.z+0.3}};}
bool Player::Collides(const Aabb& box,const World& world) const{
    constexpr double epsilon=1e-7;
    for(int y=FloorToInt(box.min.y+epsilon);y<=FloorToInt(box.max.y-epsilon);++y)
        for(int z=FloorToInt(box.min.z+epsilon);z<=FloorToInt(box.max.z-epsilon);++z)
            for(int x=FloorToInt(box.min.x+epsilon);x<=FloorToInt(box.max.x-epsilon);++x)
                if(world.IsSolid(x,y,z)&&Intersects(box,{{double(x),double(y),double(z)},{double(x+1),double(y+1),double(z+1)}}))return true;
    return false;
}
bool Player::SupportedAt(const Vec3& position,const World& world) const{
    Aabb box=BoxAt(position);
    box.min.y-=0.06;
    box.max.y=box.min.y+0.05;
    return Collides(box,world);
}
double Player::ClipAxis(const Aabb& box,double amount,int axis,const World& world) const{
    if(std::abs(amount)<1e-12)return 0.0;
    Aabb swept=box;
    if(axis==0){if(amount>0)swept.max.x+=amount;else swept.min.x+=amount;}
    if(axis==1){if(amount>0)swept.max.y+=amount;else swept.min.y+=amount;}
    if(axis==2){if(amount>0)swept.max.z+=amount;else swept.min.z+=amount;}
    constexpr double epsilon=1e-7;
    for(int y=FloorToInt(swept.min.y+epsilon);y<=FloorToInt(swept.max.y-epsilon);++y)
        for(int z=FloorToInt(swept.min.z+epsilon);z<=FloorToInt(swept.max.z-epsilon);++z)
            for(int x=FloorToInt(swept.min.x+epsilon);x<=FloorToInt(swept.max.x-epsilon);++x){
                if(!world.IsSolid(x,y,z))continue;
                const Aabb block{{double(x),double(y),double(z)},{double(x+1),double(y+1),double(z+1)}};
                if(axis==0&&box.max.y>block.min.y&&box.min.y<block.max.y&&box.max.z>block.min.z&&box.min.z<block.max.z){
                    if(amount>0&&box.max.x<=block.min.x)amount=std::min(amount,block.min.x-box.max.x);
                    else if(amount<0&&box.min.x>=block.max.x)amount=std::max(amount,block.max.x-box.min.x);
                }
                if(axis==1&&box.max.x>block.min.x&&box.min.x<block.max.x&&box.max.z>block.min.z&&box.min.z<block.max.z){
                    if(amount>0&&box.max.y<=block.min.y)amount=std::min(amount,block.min.y-box.max.y);
                    else if(amount<0&&box.min.y>=block.max.y)amount=std::max(amount,block.max.y-box.min.y);
                }
                if(axis==2&&box.max.x>block.min.x&&box.min.x<block.max.x&&box.max.y>block.min.y&&box.min.y<block.max.y){
                    if(amount>0&&box.max.z<=block.min.z)amount=std::min(amount,block.min.z-box.max.z);
                    else if(amount<0&&box.min.z>=block.max.z)amount=std::max(amount,block.max.z-box.min.z);
                }
            }
    return amount;
}

void Player::Move(Vec3 delta,const World& world){
    if(sneaking_&&onGround_){
        while(std::abs(delta.x)>0.0&&!SupportedAt({position_.x+delta.x,position_.y,position_.z},world)){if(std::abs(delta.x)<=0.05){delta.x=0;break;}delta.x+=delta.x>0?-0.05:0.05;}
        while(std::abs(delta.z)>0.0&&!SupportedAt({position_.x,position_.y,position_.z+delta.z},world)){if(std::abs(delta.z)<=0.05){delta.z=0;break;}delta.z+=delta.z>0?-0.05:0.05;}
    }

    const Vec3 requested=delta;
    const double largest=std::max({std::abs(delta.x),std::abs(delta.y),std::abs(delta.z)});
    const int steps=std::max(1,static_cast<int>(std::ceil(largest/0.25)));
    const Vec3 step=delta*(1.0/steps);
    Vec3 moved{};
    bool hitGround=false;
    bool hitHorizontal=false;

    for(int i=0;i<steps;++i){
        Aabb box=BoxAt(position_);
        const double clippedY=ClipAxis(box,step.y,1,world);
        if(step.y<0.0&&clippedY!=step.y)hitGround=true;
        if(clippedY!=step.y)velocity_.y=0.0;
        position_.y+=clippedY;
        moved.y+=clippedY;

        box=BoxAt(position_);
        const double clippedX=ClipAxis(box,step.x,0,world);
        if(clippedX!=step.x){hitHorizontal=true;velocity_.x=0.0;}
        position_.x+=clippedX;
        moved.x+=clippedX;

        box=BoxAt(position_);
        const double clippedZ=ClipAxis(box,step.z,2,world);
        if(clippedZ!=step.z){hitHorizontal=true;velocity_.z=0.0;}
        position_.z+=clippedZ;
        moved.z+=clippedZ;
    }

    onGround_=hitGround||(requested.y==0.0&&SupportedAt(position_,world));
    horizontalCollision_=hitHorizontal;
    walked_+=std::sqrt(moved.x*moved.x+moved.z*moved.z);
    cameraBob_=onGround_?std::sin(walked_*10.0)*std::min(0.06,std::sqrt(moved.x*moved.x+moved.z*moved.z)*0.45):cameraBob_*0.8;
}

void Player::Respawn(){
    position_={0.5,11.0,0.5};
    velocity_={};
    onGround_=false;
    horizontalCollision_=false;
    sneaking_=false;
    sprinting_=false;
    cameraBob_=0.0;
}

void Player::Tick(const InputState& input,const World& world){
    // Push upward if a block edit or numerical edge case ever leaves the player intersecting terrain.
    for(int attempts=0;attempts<40&&Collides(BoxAt(position_),world);++attempts)position_.y+=0.05;

    sneaking_=input.sneak;
    if(sprintTapTicks_>0)--sprintTapTicks_;
    const bool forwardPressed=input.forward&&!wasForward_;
    wasForward_=input.forward;
    if(!sneaking_&&input.forward&&(input.sprint||(forwardPressed&&sprintTapTicks_>0)))sprinting_=true;
    if(forwardPressed&&sprintTapTicks_==0)sprintTapTicks_=7;
    if(sneaking_||!input.forward||horizontalCollision_)sprinting_=false;

    double forward=(input.forward?1.0:0.0)-(input.back?1.0:0.0);
    double strafe=(input.right?1.0:0.0)-(input.left?1.0:0.0);
    if(sneaking_){forward*=0.3;strafe*=0.3;}
    const double magnitude=std::sqrt(forward*forward+strafe*strafe);
    if(magnitude>1.0){forward/=magnitude;strafe/=magnitude;}

    const double friction=onGround_?0.54600006:0.91;
    double acceleration=onGround_?0.1*(0.16277136/(friction*friction*friction)):0.02;
    if(sprinting_)acceleration*=1.3;
    const double yawRadians=yaw_*std::numbers::pi/180.0;
    const double sine=std::sin(yawRadians),cosine=std::cos(yawRadians);
    velocity_.x+=(strafe*cosine-forward*sine)*acceleration;
    velocity_.z+=(forward*cosine+strafe*sine)*acceleration;
    if(input.jump&&onGround_){velocity_.y=0.42;if(sprinting_){velocity_.x-=sine*0.2;velocity_.z+=cosine*0.2;}}

    Move(velocity_,world);
    velocity_.y-=0.08;
    velocity_.y*=0.98;
    velocity_.x*=friction;
    velocity_.z*=friction;

    if(position_.y<-15.0)Respawn();
}

void Player::ApplyMouseDelta(double dx,double dy,double sensitivity){
    const double factor=sensitivity*0.6+0.2;
    const double scale=factor*factor*factor*8.0*0.15;
    yaw_=std::fmod(yaw_-float(dx*scale),360.0f);
    pitch_=std::clamp(pitch_+float(dy*scale),-90.0f,90.0f);
}
Vec3 Player::LookDirection() const{
    const double yawRadians=yaw_*std::numbers::pi/180.0,pitchRadians=pitch_*std::numbers::pi/180.0;
    const double cosinePitch=std::cos(pitchRadians);
    return Normalize({-std::sin(yawRadians)*cosinePitch,-std::sin(pitchRadians),std::cos(yawRadians)*cosinePitch});
}
}
