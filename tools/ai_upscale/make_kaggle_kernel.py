#!/usr/bin/env python3
"""
make_kaggle_kernel.py - builds the private Kaggle notebook that trains the AI Upscale model.

Usage:
    python3 make_kaggle_kernel.py <version> [configs] [steps]
    kaggle kernels push -p ./kernel_<version>
    kaggle kernels output <user>/bannerlator-ai-upscale-<version> -p ./out_<version>

The notebook attaches our private capture dataset, installs a torch build that still supports
Kaggle's P100, embeds train_ai_upscale.py + wfgcap_dataset.py, and trains every config.
"""
import os, sys, json

HERE = os.path.dirname(os.path.abspath(__file__))
USER = json.load(open(os.path.expanduser("~/.kaggle/kaggle.json")))["username"]
DATA = f"{USER}/winfg-triplets-full-v1"

VER = sys.argv[1] if len(sys.argv) > 1 else "v1"
CONFIGS = sys.argv[2] if len(sys.argv) > 2 else "4x3,4x4,8x3,8x4"
STEPS = sys.argv[3] if len(sys.argv) > 3 else "50000"


def code(name):
    return open(os.path.join(HERE, name)).read()


def cell(*src):
    return {"cell_type": "code", "metadata": {}, "execution_count": None, "outputs": [],
            "source": list(src)}


nb = {
    "cells": [
        cell("!pip -q install qoi\n",
             "!pip -q install torch==2.5.1 --index-url https://download.pytorch.org/whl/cu121\n",
             "!python -c \"import torch; print('TORCH', torch.__version__, torch.cuda.get_device_name(0)); "
             "x=torch.randn(64,64,device='cuda'); print('CUDA_SMOKE_OK', float((x@x).sum()))\"\n"),
        cell("%%writefile wfgcap_dataset.py\n", code("wfgcap_dataset.py")),
        cell("%%writefile train_ai_upscale.py\n", code("train_ai_upscale.py")),
        cell("!python -c \"import torch; from train_ai_upscale import AiUpscale; "
             "m=AiUpscale(8,4); y=torch.rand(2,1,40,52); o=m(y); assert o.shape==(2,1,80,104), o.shape; "
             "print('SELFTEST_OK', tuple(o.shape), m.macs_per_lr_pixel())\"\n"),
        cell(f"!python train_ai_upscale.py --data /kaggle/input/winfg-triplets-full-v1 "
             f"--out /kaggle/working --configs {CONFIGS} --steps {STEPS} 2>&1 | tee /kaggle/working/train.log\n"),
        cell("import json, os\n",
             "p='/kaggle/working/summary.json'\n",
             "print('SUMMARY_FILE', json.load(open(p)) if os.path.exists(p) else None)\n"),
    ],
    "metadata": {"kernelspec": {"language": "python", "display_name": "Python 3", "name": "python3"},
                 "language_info": {"name": "python"}},
    "nbformat": 4, "nbformat_minor": 5,
}

OUT = os.path.join(os.getcwd(), f"kernel_{VER}")
os.makedirs(OUT, exist_ok=True)
json.dump(nb, open(os.path.join(OUT, "ai_upscale_train.ipynb"), "w"), indent=1)
json.dump({
    "id": f"{USER}/bannerlator-ai-upscale-{VER}",
    "title": f"bannerlator-ai-upscale-{VER}",
    "code_file": "ai_upscale_train.ipynb",
    "language": "python", "kernel_type": "notebook",
    "is_private": True, "enable_gpu": True, "enable_internet": True,
    "dataset_sources": [DATA], "competition_sources": [], "kernel_sources": [],
}, open(os.path.join(OUT, "kernel-metadata.json"), "w"), indent=2)
print("kernel", VER, "configs", CONFIGS, "steps", STEPS, "->", OUT)
