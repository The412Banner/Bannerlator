// aiup_bench.c - runs Bannerlator's AI Upscale shaders on the device GPU, outside the app.
//
// Correctness: feeds an RGBA8 image through the compute shader and dumps the rgba16f residual
// image, so it can be compared with the CPU reference in gen_shaders.py. Optionally runs the
// final pass (upscale.vert + ai_upscale_final.frag) into an RGBA8 image of any size.
// Speed: times every dispatch / draw with GPU timestamps and prints median and best in ms.
//
// usage: aiup_bench <comp.spv> <in.rgba> <w> <h> <out.res16> <iters>
//                   [<vert.spv> <final.spv> <outW> <outH> <detail> <out.rgba>]
//
// Build (Termux clang, links the system Vulkan loader):
//   clang -O2 -o aiup_bench aiup_bench.c -lvulkan
#include <vulkan/vulkan.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#define CK(x) do { VkResult r_ = (x); if (r_ != VK_SUCCESS) { \
    fprintf(stderr, "FAIL %s = %d (line %d)\n", #x, (int)r_, __LINE__); exit(1); } } while (0)

static VkDevice dev;
static VkPhysicalDevice pd;
static VkPhysicalDeviceMemoryProperties mp;

typedef struct { VkImage img; VkDeviceMemory mem; VkImageView view; } Img;
typedef struct { VkBuffer buf; VkDeviceMemory mem; void* map; VkDeviceSize size; } Buf;

static void* readFile(const char* p, size_t* sz) {
    FILE* f = fopen(p, "rb"); if (!f) { fprintf(stderr, "open %s\n", p); exit(1); }
    fseek(f, 0, SEEK_END); *sz = (size_t)ftell(f); fseek(f, 0, SEEK_SET);
    void* d = malloc(*sz); if (fread(d, 1, *sz, f) != *sz) { fprintf(stderr, "read %s\n", p); exit(1); }
    fclose(f); return d;
}
static void writeFile(const char* p, const void* d, size_t sz) {
    FILE* f = fopen(p, "wb"); if (!f) { fprintf(stderr, "write %s\n", p); exit(1); }
    fwrite(d, 1, sz, f); fclose(f);
}
static uint32_t memType(uint32_t bits, VkMemoryPropertyFlags want) {
    for (uint32_t i = 0; i < mp.memoryTypeCount; i++)
        if ((bits & (1u << i)) && (mp.memoryTypes[i].propertyFlags & want) == want) return i;
    fprintf(stderr, "no memory type\n"); exit(1);
}
static Buf mkBuf(VkDeviceSize sz, VkBufferUsageFlags use) {
    Buf b = {0}; b.size = sz;
    VkBufferCreateInfo ci = {VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO}; ci.size = sz; ci.usage = use;
    CK(vkCreateBuffer(dev, &ci, NULL, &b.buf));
    VkMemoryRequirements r; vkGetBufferMemoryRequirements(dev, b.buf, &r);
    VkMemoryAllocateInfo ai = {VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO}; ai.allocationSize = r.size;
    ai.memoryTypeIndex = memType(r.memoryTypeBits, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    CK(vkAllocateMemory(dev, &ai, NULL, &b.mem)); CK(vkBindBufferMemory(dev, b.buf, b.mem, 0));
    CK(vkMapMemory(dev, b.mem, 0, sz, 0, &b.map));
    return b;
}
static Img mkImg(int w, int h, VkFormat fmt, VkImageUsageFlags use) {
    Img m = {0};
    VkImageCreateInfo ci = {VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO}; ci.imageType = VK_IMAGE_TYPE_2D;
    ci.format = fmt; ci.extent.width = (uint32_t)w; ci.extent.height = (uint32_t)h; ci.extent.depth = 1;
    ci.mipLevels = 1; ci.arrayLayers = 1; ci.samples = VK_SAMPLE_COUNT_1_BIT; ci.tiling = VK_IMAGE_TILING_OPTIMAL;
    ci.usage = use; ci.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    CK(vkCreateImage(dev, &ci, NULL, &m.img));
    VkMemoryRequirements r; vkGetImageMemoryRequirements(dev, m.img, &r);
    VkMemoryAllocateInfo ai = {VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO}; ai.allocationSize = r.size;
    ai.memoryTypeIndex = memType(r.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    CK(vkAllocateMemory(dev, &ai, NULL, &m.mem)); CK(vkBindImageMemory(dev, m.img, m.mem, 0));
    VkImageViewCreateInfo vi = {VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO}; vi.image = m.img;
    vi.viewType = VK_IMAGE_VIEW_TYPE_2D; vi.format = fmt;
    vi.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT; vi.subresourceRange.levelCount = 1; vi.subresourceRange.layerCount = 1;
    CK(vkCreateImageView(dev, &vi, NULL, &m.view));
    return m;
}
static VkShaderModule mkShader(const char* path) {
    size_t sz; void* code = readFile(path, &sz);
    VkShaderModuleCreateInfo ci = {VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO}; ci.codeSize = sz; ci.pCode = code;
    VkShaderModule m; CK(vkCreateShaderModule(dev, &ci, NULL, &m)); free(code); return m;
}
static void imgBarrier(VkCommandBuffer cb, VkImage img, VkImageLayout o, VkImageLayout n,
                       VkAccessFlags sa, VkAccessFlags da, VkPipelineStageFlags ss, VkPipelineStageFlags ds) {
    VkImageMemoryBarrier b = {VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
    b.oldLayout = o; b.newLayout = n; b.srcAccessMask = sa; b.dstAccessMask = da;
    b.srcQueueFamilyIndex = b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED; b.image = img;
    b.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT; b.subresourceRange.levelCount = 1; b.subresourceRange.layerCount = 1;
    vkCmdPipelineBarrier(cb, ss, ds, 0, 0, NULL, 0, NULL, 1, &b);
}
static int cmpd(const void* a, const void* b) { double x = *(const double*)a, y = *(const double*)b; return x < y ? -1 : x > y; }
static void report(const char* what, uint64_t* ts, int n, float period) {
    double* ms = malloc(sizeof(double) * (size_t)n);
    for (int i = 0; i < n; i++) ms[i] = (double)(ts[2 * i + 1] - ts[2 * i]) * period / 1e6;
    qsort(ms, (size_t)n, sizeof(double), cmpd);
    printf("TIMING %s median=%.3f ms best=%.3f ms (n=%d)\n", what, ms[n / 2], ms[0], n);
    free(ms);
}

int main(int argc, char** argv) {
    setvbuf(stdout, NULL, _IONBF, 0);
    if (argc < 7) { fprintf(stderr, "usage: see header\n"); return 2; }
    const char* compPath = argv[1]; const char* inPath = argv[2];
    int W = atoi(argv[3]), H = atoi(argv[4]); const char* resOut = argv[5]; int iters = atoi(argv[6]);
    int doFinal = argc >= 13;
    const char *vertPath = NULL, *fragPath = NULL, *outPath = NULL; int OW = 0, OH = 0; float detail = 1.f;
    if (doFinal) { vertPath = argv[7]; fragPath = argv[8]; OW = atoi(argv[9]); OH = atoi(argv[10]);
                   detail = (float)atof(argv[11]); outPath = argv[12]; }

    VkApplicationInfo app = {VK_STRUCTURE_TYPE_APPLICATION_INFO}; app.apiVersion = VK_API_VERSION_1_1;
    VkInstanceCreateInfo ici = {VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO}; ici.pApplicationInfo = &app;
    printf("stage: instance\n");
    VkInstance inst; CK(vkCreateInstance(&ici, NULL, &inst));
    uint32_t n = 1; CK(vkEnumeratePhysicalDevices(inst, &n, &pd)); if (!n) { fprintf(stderr, "no gpu\n"); return 1; }
    VkPhysicalDeviceProperties props; vkGetPhysicalDeviceProperties(pd, &props);
    vkGetPhysicalDeviceMemoryProperties(pd, &mp);
    printf("DEVICE %s | api %u.%u | driver 0x%x | maxSharedMem %u | maxWGInvocations %u | tsPeriod %.2f ns\n",
           props.deviceName, VK_VERSION_MAJOR(props.apiVersion), VK_VERSION_MINOR(props.apiVersion),
           props.driverVersion, props.limits.maxComputeSharedMemorySize,
           props.limits.maxComputeWorkGroupInvocations, props.limits.timestampPeriod);
    VkFormatProperties fp; vkGetPhysicalDeviceFormatProperties(pd, VK_FORMAT_R16G16B16A16_SFLOAT, &fp);
    printf("RGBA16F optimal: storage=%d sampled=%d\n",
           !!(fp.optimalTilingFeatures & VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT),
           !!(fp.optimalTilingFeatures & VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT));

    uint32_t qfn = 0; vkGetPhysicalDeviceQueueFamilyProperties(pd, &qfn, NULL);
    VkQueueFamilyProperties* qf = malloc(sizeof(*qf) * qfn); vkGetPhysicalDeviceQueueFamilyProperties(pd, &qfn, qf);
    uint32_t qi = 0; while (qi < qfn && !((qf[qi].queueFlags & VK_QUEUE_GRAPHICS_BIT) && (qf[qi].queueFlags & VK_QUEUE_COMPUTE_BIT))) qi++;
    float prio = 1.f;
    VkDeviceQueueCreateInfo dq = {VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO}; dq.queueFamilyIndex = qi; dq.queueCount = 1; dq.pQueuePriorities = &prio;
    VkDeviceCreateInfo dci = {VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO}; dci.queueCreateInfoCount = 1; dci.pQueueCreateInfos = &dq;
    CK(vkCreateDevice(pd, &dci, NULL, &dev));
    VkQueue q; vkGetDeviceQueue(dev, qi, 0, &q);
    VkCommandPoolCreateInfo cpi = {VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO}; cpi.queueFamilyIndex = qi;
    VkCommandPool pool; CK(vkCreateCommandPool(dev, &cpi, NULL, &pool));
    VkCommandBufferAllocateInfo cai = {VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO}; cai.commandPool = pool;
    cai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY; cai.commandBufferCount = 1;
    VkCommandBuffer cb; CK(vkAllocateCommandBuffers(dev, &cai, &cb));

    // resources
    size_t inSz; void* inPx = readFile(inPath, &inSz);
    if (inSz != (size_t)W * H * 4) { fprintf(stderr, "input size %zu != %d*%d*4\n", inSz, W, H); return 1; }
    Buf up = mkBuf(inSz, VK_BUFFER_USAGE_TRANSFER_SRC_BIT); memcpy(up.map, inPx, inSz);
    Img src = mkImg(W, H, VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT);
    Img res = mkImg(W, H, VK_FORMAT_R16G16B16A16_SFLOAT,
                    VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT);
    Buf resRb = mkBuf((VkDeviceSize)W * H * 8, VK_BUFFER_USAGE_TRANSFER_DST_BIT);
    VkSamplerCreateInfo sci = {VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO}; sci.magFilter = sci.minFilter = VK_FILTER_LINEAR;
    sci.addressModeU = sci.addressModeV = sci.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    VkSampler smp; CK(vkCreateSampler(dev, &sci, NULL, &smp));

    // compute pipeline
    VkDescriptorSetLayoutBinding cb2[2] = {{0}};
    cb2[0].binding = 0; cb2[0].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER; cb2[0].descriptorCount = 1; cb2[0].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    cb2[1].binding = 1; cb2[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE; cb2[1].descriptorCount = 1; cb2[1].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    VkDescriptorSetLayoutCreateInfo dli = {VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO}; dli.bindingCount = 2; dli.pBindings = cb2;
    VkDescriptorSetLayout cdsl; CK(vkCreateDescriptorSetLayout(dev, &dli, NULL, &cdsl));
    VkDescriptorSetLayoutBinding fb1 = {0}; fb1.binding = 0; fb1.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    fb1.descriptorCount = 1; fb1.stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;
    VkDescriptorSetLayoutCreateInfo fli = {VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO}; fli.bindingCount = 1; fli.pBindings = &fb1;
    VkDescriptorSetLayout fdsl; CK(vkCreateDescriptorSetLayout(dev, &fli, NULL, &fdsl));
    VkDescriptorPoolSize ps[2] = {{VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 3}, {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 1}};
    VkDescriptorPoolCreateInfo dpi = {VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO}; dpi.maxSets = 3; dpi.poolSizeCount = 2; dpi.pPoolSizes = ps;
    VkDescriptorPool dp; CK(vkCreateDescriptorPool(dev, &dpi, NULL, &dp));
    VkDescriptorSetLayout lays3[3] = {cdsl, fdsl, fdsl};
    VkDescriptorSetAllocateInfo dsa = {VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO}; dsa.descriptorPool = dp; dsa.descriptorSetCount = 3; dsa.pSetLayouts = lays3;
    VkDescriptorSet sets[3]; CK(vkAllocateDescriptorSets(dev, &dsa, sets));
    VkDescriptorImageInfo iSrc = {smp, src.view, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
    VkDescriptorImageInfo iResW = {VK_NULL_HANDLE, res.view, VK_IMAGE_LAYOUT_GENERAL};
    VkDescriptorImageInfo iResR = {smp, res.view, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
    VkWriteDescriptorSet wr[4] = {{0}};
    for (int i = 0; i < 4; i++) { wr[i].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET; wr[i].descriptorCount = 1; }
    wr[0].dstSet = sets[0]; wr[0].dstBinding = 0; wr[0].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER; wr[0].pImageInfo = &iSrc;
    wr[1].dstSet = sets[0]; wr[1].dstBinding = 1; wr[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE; wr[1].pImageInfo = &iResW;
    wr[2].dstSet = sets[1]; wr[2].dstBinding = 0; wr[2].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER; wr[2].pImageInfo = &iSrc;
    wr[3].dstSet = sets[2]; wr[3].dstBinding = 0; wr[3].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER; wr[3].pImageInfo = &iResR;
    vkUpdateDescriptorSets(dev, 4, wr, 0, NULL);
    VkPushConstantRange cpc = {VK_SHADER_STAGE_COMPUTE_BIT, 0, 8};
    VkPipelineLayoutCreateInfo cpli = {VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO}; cpli.setLayoutCount = 1; cpli.pSetLayouts = &cdsl;
    cpli.pushConstantRangeCount = 1; cpli.pPushConstantRanges = &cpc;
    VkPipelineLayout cpl; CK(vkCreatePipelineLayout(dev, &cpli, NULL, &cpl));
    VkComputePipelineCreateInfo cpci = {VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO};
    cpci.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO; cpci.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    cpci.stage.module = mkShader(compPath); cpci.stage.pName = "main"; cpci.layout = cpl;
    printf("stage: compute pipeline\n");
    struct timespec t0, t1; clock_gettime(CLOCK_MONOTONIC, &t0);
    VkPipeline cpipe; CK(vkCreateComputePipelines(dev, VK_NULL_HANDLE, 1, &cpci, NULL, &cpipe));
    clock_gettime(CLOCK_MONOTONIC, &t1);
    printf("PIPELINE compute create %.1f ms\n", (t1.tv_sec - t0.tv_sec) * 1e3 + (t1.tv_nsec - t0.tv_nsec) / 1e6);

    // final pass pipeline (optional)
    Img out = {0}; Buf outRb = {0}; VkRenderPass rp = VK_NULL_HANDLE; VkFramebuffer fbo = VK_NULL_HANDLE;
    VkPipeline gpipe = VK_NULL_HANDLE; VkPipelineLayout gpl = VK_NULL_HANDLE;
    if (doFinal) {
        out = mkImg(OW, OH, VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT);
        outRb = mkBuf((VkDeviceSize)OW * OH * 4, VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        VkAttachmentDescription att = {0}; att.format = VK_FORMAT_R8G8B8A8_UNORM; att.samples = VK_SAMPLE_COUNT_1_BIT;
        att.loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR; att.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
        att.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE; att.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
        att.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED; att.finalLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
        VkAttachmentReference ref = {0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
        VkSubpassDescription sub = {0}; sub.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS; sub.colorAttachmentCount = 1; sub.pColorAttachments = &ref;
        VkRenderPassCreateInfo rpi = {VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO}; rpi.attachmentCount = 1; rpi.pAttachments = &att; rpi.subpassCount = 1; rpi.pSubpasses = &sub;
        CK(vkCreateRenderPass(dev, &rpi, NULL, &rp));
        VkFramebufferCreateInfo fci = {VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO}; fci.renderPass = rp; fci.attachmentCount = 1;
        fci.pAttachments = &out.view; fci.width = (uint32_t)OW; fci.height = (uint32_t)OH; fci.layers = 1;
        CK(vkCreateFramebuffer(dev, &fci, NULL, &fbo));
        VkPushConstantRange gpc = {VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0, 36};
        VkDescriptorSetLayout two[2] = {fdsl, fdsl};
        VkPipelineLayoutCreateInfo gpli = {VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO}; gpli.setLayoutCount = 2; gpli.pSetLayouts = two;
        gpli.pushConstantRangeCount = 1; gpli.pPushConstantRanges = &gpc;
        CK(vkCreatePipelineLayout(dev, &gpli, NULL, &gpl));
        VkPipelineShaderStageCreateInfo st[2] = {{0}};
        st[0].sType = st[1].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        st[0].stage = VK_SHADER_STAGE_VERTEX_BIT; st[0].module = mkShader(vertPath); st[0].pName = "main";
        st[1].stage = VK_SHADER_STAGE_FRAGMENT_BIT; st[1].module = mkShader(fragPath); st[1].pName = "main";
        VkPipelineVertexInputStateCreateInfo vis = {VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO};
        VkPipelineInputAssemblyStateCreateInfo ias = {VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO}; ias.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP;
        VkViewport vp = {0, 0, (float)OW, (float)OH, 0, 1}; VkRect2D sc = {{0, 0}, {(uint32_t)OW, (uint32_t)OH}};
        VkPipelineViewportStateCreateInfo vps = {VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO}; vps.viewportCount = 1; vps.pViewports = &vp; vps.scissorCount = 1; vps.pScissors = &sc;
        VkPipelineRasterizationStateCreateInfo rs = {VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO}; rs.polygonMode = VK_POLYGON_MODE_FILL; rs.lineWidth = 1.f; rs.cullMode = VK_CULL_MODE_NONE;
        VkPipelineMultisampleStateCreateInfo mss = {VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO}; mss.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;
        VkPipelineColorBlendAttachmentState ba = {0}; ba.colorWriteMask = 0xF;
        VkPipelineColorBlendStateCreateInfo cbs = {VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO}; cbs.attachmentCount = 1; cbs.pAttachments = &ba;
        VkGraphicsPipelineCreateInfo gci = {VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO}; gci.stageCount = 2; gci.pStages = st;
        gci.pVertexInputState = &vis; gci.pInputAssemblyState = &ias; gci.pViewportState = &vps; gci.pRasterizationState = &rs;
        gci.pMultisampleState = &mss; gci.pColorBlendState = &cbs; gci.layout = gpl; gci.renderPass = rp;
        CK(vkCreateGraphicsPipelines(dev, VK_NULL_HANDLE, 1, &gci, NULL, &gpipe));
    }

    VkQueryPoolCreateInfo qpi = {VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO}; qpi.queryType = VK_QUERY_TYPE_TIMESTAMP;
    qpi.queryCount = (uint32_t)(4 * iters);
    VkQueryPool qp; CK(vkCreateQueryPool(dev, &qpi, NULL, &qp));

    VkCommandBufferBeginInfo bi = {VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    CK(vkBeginCommandBuffer(cb, &bi));
    vkCmdResetQueryPool(cb, qp, 0, (uint32_t)(4 * iters));
    imgBarrier(cb, src.img, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 0, VK_ACCESS_TRANSFER_WRITE_BIT,
               VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
    VkBufferImageCopy cr = {0}; cr.imageSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT; cr.imageSubresource.layerCount = 1;
    cr.imageExtent.width = (uint32_t)W; cr.imageExtent.height = (uint32_t)H; cr.imageExtent.depth = 1;
    vkCmdCopyBufferToImage(cb, up.buf, src.img, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &cr);
    imgBarrier(cb, src.img, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
               VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
               VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT);
    int32_t size[2] = {W, H};
    for (int i = 0; i < iters; i++) {
        imgBarrier(cb, res.img, i == 0 ? VK_IMAGE_LAYOUT_UNDEFINED : VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                   VK_IMAGE_LAYOUT_GENERAL, VK_ACCESS_SHADER_READ_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                   VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
        vkCmdWriteTimestamp(cb, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, qp, (uint32_t)(4 * i));
        vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, cpipe);
        vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_COMPUTE, cpl, 0, 1, &sets[0], 0, NULL);
        vkCmdPushConstants(cb, cpl, VK_SHADER_STAGE_COMPUTE_BIT, 0, 8, size);
        vkCmdDispatch(cb, (uint32_t)((W + 15) / 16), (uint32_t)((H + 15) / 16), 1);
        vkCmdWriteTimestamp(cb, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, qp, (uint32_t)(4 * i + 1));
        imgBarrier(cb, res.img, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                   VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                   VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
        if (!doFinal) {   // keep every query written so the WAIT_BIT readback can't block
            vkCmdWriteTimestamp(cb, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, qp, (uint32_t)(4 * i + 2));
            vkCmdWriteTimestamp(cb, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, qp, (uint32_t)(4 * i + 3));
        }
        if (doFinal) {
            vkCmdWriteTimestamp(cb, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, qp, (uint32_t)(4 * i + 2));
            VkClearValue clr = {{{0, 0, 0, 1}}};
            VkRenderPassBeginInfo rbi = {VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO}; rbi.renderPass = rp; rbi.framebuffer = fbo;
            rbi.renderArea.extent.width = (uint32_t)OW; rbi.renderArea.extent.height = (uint32_t)OH; rbi.clearValueCount = 1; rbi.pClearValues = &clr;
            vkCmdBeginRenderPass(cb, &rbi, VK_SUBPASS_CONTENTS_INLINE);
            vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, gpipe);
            VkDescriptorSet fs[2] = {sets[1], sets[2]};
            vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, gpl, 0, 2, fs, 0, NULL);
            float pcd[9] = {-1, -1, 1, 1, 1.f / W, 1.f / H, (float)W, (float)H, detail};
            vkCmdPushConstants(cb, gpl, VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0, 36, pcd);
            vkCmdDraw(cb, 4, 1, 0, 0);
            vkCmdEndRenderPass(cb);
            vkCmdWriteTimestamp(cb, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, qp, (uint32_t)(4 * i + 3));
            if (i + 1 < iters)
                imgBarrier(cb, out.img, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, 0,
                           VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                           VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT);
        }
    }
    imgBarrier(cb, res.img, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
               VK_ACCESS_SHADER_READ_BIT, VK_ACCESS_TRANSFER_READ_BIT,
               VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
    vkCmdCopyImageToBuffer(cb, res.img, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, resRb.buf, 1, &cr);
    if (doFinal) {
        VkBufferImageCopy orr = cr; orr.imageExtent.width = (uint32_t)OW; orr.imageExtent.height = (uint32_t)OH;
        vkCmdCopyImageToBuffer(cb, out.img, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, outRb.buf, 1, &orr);
    }
    CK(vkEndCommandBuffer(cb));
    VkFenceCreateInfo fci2 = {VK_STRUCTURE_TYPE_FENCE_CREATE_INFO}; VkFence fence; CK(vkCreateFence(dev, &fci2, NULL, &fence));
    VkSubmitInfo si = {VK_STRUCTURE_TYPE_SUBMIT_INFO}; si.commandBufferCount = 1; si.pCommandBuffers = &cb;
    printf("stage: submit\n");
    CK(vkQueueSubmit(q, 1, &si, fence)); CK(vkWaitForFences(dev, 1, &fence, VK_TRUE, 60ull * 1000000000ull));

    uint64_t* ts = calloc((size_t)(4 * iters), sizeof(uint64_t));
    CK(vkGetQueryPoolResults(dev, qp, 0, (uint32_t)(4 * iters), sizeof(uint64_t) * 4 * iters, ts, sizeof(uint64_t),
                             VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WAIT_BIT));
    uint64_t* tc = malloc(sizeof(uint64_t) * 2 * iters); uint64_t* tf = malloc(sizeof(uint64_t) * 2 * iters);
    for (int i = 0; i < iters; i++) { tc[2*i] = ts[4*i]; tc[2*i+1] = ts[4*i+1]; tf[2*i] = ts[4*i+2]; tf[2*i+1] = ts[4*i+3]; }
    char label[64]; snprintf(label, sizeof label, "compute %dx%d", W, H); report(label, tc, iters, props.limits.timestampPeriod);
    if (doFinal) { snprintf(label, sizeof label, "final %dx%d", OW, OH); report(label, tf, iters, props.limits.timestampPeriod); }
    writeFile(resOut, resRb.map, (size_t)W * H * 8);
    if (doFinal) writeFile(outPath, outRb.map, (size_t)OW * OH * 4);
    printf("OK\n");
    return 0;
}
