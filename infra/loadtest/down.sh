#!/usr/bin/env bash
# Project=loopers-loadtest 태그가 붙은 인스턴스와 보안 그룹·키 페어를 모두 지운다. EBS 는 인스턴스와 함께 지워진다.
source "$(dirname "$0")/common.sh"

IDS=$(aws ec2 describe-instances \
  --filters "Name=tag:Project,Values=$PREFIX" "Name=instance-state-name,Values=pending,running,stopping,stopped" \
  --query 'Reservations[].Instances[].InstanceId' --output text)
if [[ -n "$IDS" ]]; then
  echo "terminate: $IDS"
  aws ec2 terminate-instances --instance-ids $IDS > /dev/null
  aws ec2 wait instance-terminated --instance-ids $IDS
fi

SG=$(aws ec2 describe-security-groups --filters "Name=group-name,Values=$PREFIX" --query 'SecurityGroups[0].GroupId' --output text)
if [[ "$SG" != "None" ]]; then
  echo "delete security group: $SG"
  aws ec2 delete-security-group --group-id "$SG"
fi

aws ec2 delete-key-pair --key-name "$PREFIX" > /dev/null 2>&1 || true
rm -rf "$STATE"

echo "남은 리소스:"
aws ec2 describe-instances --filters "Name=tag:Project,Values=$PREFIX" "Name=instance-state-name,Values=pending,running,stopping,stopped" \
  --query 'length(Reservations[].Instances[])'
