#!/usr/bin/env bash
# 키 페어·보안 그룹·EC2 6대를 만든다. 결과 IP 는 .state/hosts.env 에 남긴다.
source "$(dirname "$0")/common.sh"

if [[ -f "$HOSTS" ]]; then
  echo "이미 만들어져 있다 ($HOSTS). 다시 만들려면 down.sh 를 먼저 실행한다." >&2
  exit 1
fi

MY_IP=$(curl -s https://checkip.amazonaws.com)
VPC=$(aws ec2 describe-vpcs --filters Name=isDefault,Values=true --query 'Vpcs[0].VpcId' --output text)
SUBNET=$(aws ec2 describe-subnets \
  --filters Name=vpc-id,Values="$VPC" Name=availability-zone,Values="$AZ" Name=default-for-az,Values=true \
  --query 'Subnets[0].SubnetId' --output text)
AMI=$(aws ec2 describe-images --owners amazon \
  --filters 'Name=name,Values=al2023-ami-2023.*-kernel-6.1-x86_64' 'Name=state,Values=available' \
  --query 'sort_by(Images,&CreationDate)[-1].ImageId' --output text)
echo "vpc=$VPC subnet=$SUBNET ami=$AMI ssh-from=$MY_IP/32"

aws ec2 create-key-pair --key-name "$PREFIX" --key-type ed25519 \
  --tag-specifications "ResourceType=key-pair,Tags=[{Key=Project,Value=$PREFIX}]" \
  --query KeyMaterial --output text > "$KEY"
chmod 400 "$KEY"

SG=$(aws ec2 create-security-group --group-name "$PREFIX" --description "$PREFIX" --vpc-id "$VPC" \
  --tag-specifications "ResourceType=security-group,Tags=[{Key=Project,Value=$PREFIX}]" \
  --query GroupId --output text)
# 22 는 지금 이 PC 의 공인 IP 에서만. 나머지는 같은 보안 그룹 안에서만.
aws ec2 authorize-security-group-ingress --group-id "$SG" --ip-permissions \
  "IpProtocol=tcp,FromPort=22,ToPort=22,IpRanges=[{CidrIp=$MY_IP/32}]" \
  "IpProtocol=tcp,FromPort=8080,ToPort=8081,UserIdGroupPairs=[{GroupId=$SG}]" \
  "IpProtocol=tcp,FromPort=3306,ToPort=3306,UserIdGroupPairs=[{GroupId=$SG}]" \
  "IpProtocol=tcp,FromPort=6379,ToPort=6379,UserIdGroupPairs=[{GroupId=$SG}]" > /dev/null

launch() { # name type userdata volume-gb
  aws ec2 run-instances --image-id "$AMI" --instance-type "$2" --key-name "$PREFIX" \
    --security-group-ids "$SG" --subnet-id "$SUBNET" \
    --user-data "file://$HERE/userdata/$3" \
    --block-device-mappings "DeviceName=/dev/xvda,Ebs={VolumeSize=$4,VolumeType=gp3,DeleteOnTermination=true}" \
    --tag-specifications \
      "ResourceType=instance,Tags=[{Key=Name,Value=$PREFIX-$1},{Key=Project,Value=$PREFIX},{Key=Role,Value=$1}]" \
      "ResourceType=volume,Tags=[{Key=Project,Value=$PREFIX}]" \
    --query 'Instances[0].InstanceId' --output text
}

declare -a IDS
IDS+=("$(launch mysql c7i.xlarge mysql.sh 30)")
IDS+=("$(launch redis c7i.large redis.sh 30)")
IDS+=("$(launch app-1 c7i.large app.sh 30)")
IDS+=("$(launch app-2 c7i.large app.sh 30)")
IDS+=("$(launch app-3 c7i.large app.sh 30)")
IDS+=("$(launch k6 c7i.xlarge k6.sh 30)")
echo "instances: ${IDS[*]}"

aws ec2 wait instance-running --instance-ids "${IDS[@]}"

{
  echo "SG=$SG"
  aws ec2 describe-instances --instance-ids "${IDS[@]}" \
    --query 'Reservations[].Instances[].[Tags[?Key==`Role`]|[0].Value, PublicIpAddress, PrivateIpAddress]' --output text |
  while read -r role public private; do
    var=$(echo "$role" | tr 'a-z-' 'A-Z_')
    echo "${var}_PUBLIC=$public"
    echo "${var}_PRIVATE=$private"
  done
} > "$HOSTS"

cat "$HOSTS"
echo "다음: deploy.sh (user-data 설치가 끝날 때까지 기다린 뒤 앱을 올린다)"
