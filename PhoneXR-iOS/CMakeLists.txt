cmake_minimum_required(VERSION 3.22)
project(PhoneXR LANGUAGES Swift)

set(CMAKE_Swift_LANGUAGE_VERSION 5)
set(CMAKE_XCODE_ATTRIBUTE_IPHONEOS_DEPLOYMENT_TARGET "17.0")

add_executable(PhoneXR MACOSX_BUNDLE
    Sources/PhoneXRApp.swift
    Sources/ContentView.swift
    Sources/GameLibrary.swift
    Sources/APKAnalyzer.swift
    Sources/HandTrackingView.swift
)

set_target_properties(PhoneXR PROPERTIES
    MACOSX_BUNDLE_INFO_PLIST "${CMAKE_CURRENT_SOURCE_DIR}/Resources/Info.plist"
    XCODE_ATTRIBUTE_PRODUCT_BUNDLE_IDENTIFIER "com.samrat.PhoneXR.iOS"
    XCODE_ATTRIBUTE_PRODUCT_NAME "PhoneXR"
    XCODE_ATTRIBUTE_SWIFT_VERSION "5.0"
    XCODE_ATTRIBUTE_TARGETED_DEVICE_FAMILY "1,2"
    XCODE_ATTRIBUTE_GENERATE_INFOPLIST_FILE "NO"
)

target_link_libraries(PhoneXR PRIVATE
    "-framework SwiftUI"
    "-framework UIKit"
    "-framework AVFoundation"
    "-framework Vision"
)
