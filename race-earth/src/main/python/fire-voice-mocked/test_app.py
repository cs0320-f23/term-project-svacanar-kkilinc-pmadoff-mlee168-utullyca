import pytest
import requests
import json
import subprocess
import os
import time

BASE_URL = "http://localhost:5000"
API_SCRIPT_PATH = "src/main/python/fire-voice-mocked/apiV1.py"  # Update with the actual path

@pytest.fixture(scope="session", autouse=True)
def start_stop_server():
    # Start the server
    proc = subprocess.Popen(["python", API_SCRIPT_PATH], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    time.sleep(5)  # Wait for the server to start
    yield
    # Stop the server
    requests.get(f"{BASE_URL}/stopServer")
    proc.terminate()
    proc.wait()

def save_response(response, filename):
    if os.path.exists(filename):
        os.remove(filename)
    with open(filename, "w") as file:
        json.dump(response.json(), file, indent=4)

def test_get_process():
    response = requests.get(f"{BASE_URL}/process")
    assert response.status_code == 200
    save_response(response, "get_process_response.json")

def test_post_process():
    response = requests.post(f"{BASE_URL}/process", data={"filepath": "dummy/path"})
    assert response.status_code == 200
    assert isinstance(response.json(), dict)
    save_response(response, "post_process_response.json")

def test_stop_server():
    response = requests.get(f"{BASE_URL}/stopServer")
    assert response.status_code == 200
    assert response.json() == {"success": True, "message": "Server has been shut down"}
    save_response(response, "stop_server_response.json")

# Run the pytest command to execute tests
# pytest test_api.py
