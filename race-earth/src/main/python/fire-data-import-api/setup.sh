#!/bin/bash

# Project directory
PROJECT_DIR="fire_data_project"
APP_DIR="$PROJECT_DIR/app"
DATA_DIR="$PROJECT_DIR/data/json"

# Create project structure
mkdir -p "$PROJECT_DIR"
cd "$PROJECT_DIR" || exit

# Create Python virtual environment
python3 -m venv venv
source venv/bin/activate

# Create directories
mkdir -p "$APP_DIR/api" "$APP_DIR/services" "$APP_DIR/config" "$APP_DIR/models" "$DATA_DIR"

# Create __init__.py files for Python packages
touch "$APP_DIR/__init__.py" \
      "$APP_DIR/api/__init__.py" \
      "$APP_DIR/services/__init__.py" \
      "$APP_DIR/config/__init__.py" \
      "$APP_DIR/models/__init__.py"

# Create main files
touch "$APP_DIR/api/routes.py" \
      "$APP_DIR/services/social_media_collector.py" \
      "$APP_DIR/config/config.py" \
      "$APP_DIR/models/data_model.py" \
      "$PROJECT_DIR/.env" \
      "$PROJECT_DIR/.gitignore" \
      "$PROJECT_DIR/requirements.txt" \
      "$PROJECT_DIR/main.py"