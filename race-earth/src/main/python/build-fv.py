import os
import subprocess
import sys
import stat

def resolve_repo_root():
    current_dir = os.path.dirname(os.path.abspath(__file__))
    return os.path.abspath(os.path.join(current_dir, "../../../../"))

def run_command(command):
    try:
        result = subprocess.run(command, shell=True, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, universal_newlines=True)
        print(result.stdout)
        return True
    except subprocess.CalledProcessError as e:
        print("Error:", e.stderr)
        print("An error occurred while executing command:", e.cmd)
        return False

def change_permissions_and_delete(path):
    try:
        os.chmod(path, stat.S_IWUSR | stat.S_IRUSR)
        if os.path.isdir(path):
            os.rmdir(path)
        else:
            os.remove(path)
        return True
    except OSError as e:
        print(f"Error changing permissions or deleting file: {e}")
        return False

def delete_directory(dir_path):
    for root, dirs, files in os.walk(dir_path, topdown=False):
        for name in files:
            file_path = os.path.join(root, name)
            if not change_permissions_and_delete(file_path):
                return False
        for name in dirs:
            dir_path = os.path.join(root, name)
            if not change_permissions_and_delete(dir_path):
                return False
    return change_permissions_and_delete(dir_path)

def create_directory(dir_path):
    try:
        os.makedirs(dir_path, exist_ok=True)
        return True
    except OSError as e:
        print(f"Error creating directory: {e}")
        return False

repo_root = resolve_repo_root()
conda_env_path = os.path.join(repo_root, "race-earth/src/main/python/conda_env")

print("Script execution started.")
print(f"Repository root resolved as: {repo_root}")

if os.path.isdir(conda_env_path):
    print("Cleaning up the existing conda environment directory...")
    if not delete_directory(conda_env_path):
        print("Manual intervention required to delete the conda environment directory.")
        sys.exit(1)
else:
    print("Creating conda environment directory...")
    if not create_directory(conda_env_path):
        print("Failed to create the conda environment directory.")
        sys.exit(1)

print(f"Creating a new conda environment at {conda_env_path} with Python 3.10...")
if not run_command(f"conda create --prefix {conda_env_path} python=3.10 -y"):
    sys.exit(1)

activate_command = f"conda activate {conda_env_path} && pip install openai flask ipykernel requests"
print("Activating the conda environment and installing required packages...")
if not run_command(activate_command):
    print("Please activate the conda environment manually using:")
    print(f"conda activate {conda_env_path}")
    print("After activating, install the required packages using:")
    print("pip install openai flask ipykernel")
    sys.exit(1)

print("Conda environment setup and package installation are complete.")
