#pragma once
// ============================================================================
// winfg_vkd — fill win-fg's own dispatch tables from the renderer's VkTable.
//
// win-fg's chain (framegen.cpp / record_impl.inc) was written as a Vulkan
// LAYER and calls through winfg::DeviceDispatch / InstanceDispatch. Inside the
// compositor there is no layer chain to resolve those from, so they are filled
// from the renderer's per-context table instead - the same bridge lsfg_vkd
// provides for the LSFG chain, for the same reason: an adrenotools driver
// shares no global symbols with the system loader.
//
// Only the entry points the chain actually calls are filled (36 device-level
// plus GetPhysicalDeviceMemoryProperties); everything else in the structs
// stays null and is never reached. winfgVkdInit reports a false if any of
// those failed to resolve, so a null is never called mid-frame.
// ============================================================================
#include "vk_dispatch.hpp"
struct VkTable;
bool winfgVkdInit(const VkTable& table, winfg::DeviceDispatch& dd, winfg::InstanceDispatch& id);
