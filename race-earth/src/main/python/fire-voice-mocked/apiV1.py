from flask import Flask, jsonify, request, Response
import json
import logging
import random
import os


app = Flask(__name__)

# Load mock data (single file)
def load_mock_data():
    fp = "mockedFireVoiceData.json"
    with open(fp) as file:
        mock_data = json.load(file)
        logging.info('Loading mock data from file: ' + str(fp))
        logging.info('Loaded mock data: ' + str(mock_data))
    return mock_data

mock_data = load_mock_data()

# Set up logging
logging.basicConfig(filename='mock_api.log', level=logging.INFO, format='%(asctime)s:%(levelname)s:%(message)s')

@app.route('/process', methods=['GET', 'POST'])
def process_call():
    if request.method == 'GET':
        logging.info('Received test GET request')
        return Response(status=200)

    try:
        logging.info('Received POST request with data: ' + str(request.json))
        # Select a random response from mock_data
        response = random.choice(mock_data)
        logging.info('Responding with mock data: ' + str(response))
        return jsonify(response)
    except Exception as e:
        logging.error(f"Error processing request: {e}")
        return jsonify({"error": "Internal server error"}), 500

@app.route('/stopServer', methods=['GET'])
def stop_server():
    logging.info('Server shutdown request received')
    return jsonify({"success": True, "message": "Server has been shut down"})

if __name__ == "__main__":
    app.run(debug=True, port=5000)
