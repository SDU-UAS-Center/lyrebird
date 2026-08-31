cmake_minimum_required(VERSION 3.8)
project(lyrebird_msgs)

if(CMAKE_COMPILER_IS_GNUCXX OR CMAKE_CXX_COMPILER_ID MATCHES "Clang")
  add_compile_options(-Wall -Wextra -Wpedantic)
endif()

find_package(ament_cmake REQUIRED)
find_package(rosidl_default_generators REQUIRED)

rosidl_generate_interfaces(${PROJECT_NAME}
  "msg/VehicleCommand.msg"
  "msg/VehicleCommandAck.msg"
  "msg/TrajectorySetpoint.msg"
  "msg/ManualControlSetpoint.msg"
  "msg/BatteryStatus.msg"
  "msg/VehicleGlobalPosition.msg"
  "msg/HomePosition.msg"
  "msg/VehicleLocalPosition.msg"
  "msg/VehicleStatus.msg"
  "msg/GimbalStatus.msg"
  "msg/CameraStatus.msg"
  "msg/MissionResult.msg"
)

ament_package()
