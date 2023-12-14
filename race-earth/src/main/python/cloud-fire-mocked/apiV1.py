from flask import Flask, jsonify, request, Response
import json
import logging
import random
import os

log_file = 'mock_cf_api.log'
if not os.path.exists(log_file):
    with open(log_file, 'w') as f:
        pass

app = Flask(__name__)

files_in_folder = os.listdir('mockedCloudFireData')

loaded_files = {}

for file_name in files_in_folder:
    file_path = os.path.join('mockedCloudFireData', file_name)
    if os.path.isfile(file_path):
        with open(file_path) as file:
            file_content = json.load(file)
            loaded_files[file_name] = file_content

logging.basicConfig(filename=log_file, level=logging.INFO, format='%(asctime)s:%(levelname)s:%(message)s')

def extract_coordinates(name):
    try:
        lat_start = name.find('lat=') + 4
        lat_end = name.find('_lon=')
        lon_start = lat_end + 5
        lon_end = name.find('.json')
        lat = float(name[lat_start:lat_end])
        lon = float(name[lon_start:lon_end])
        return lat, lon
    except ValueError as e:
        logging.error(f"Error extracting coordinates from file name '{name}': {e}")
        return None, None

@app.route('/check_available', methods=['GET'])
def check_available():
    logging.info('Check available request received')
    return jsonify({"success": True, "message": "Service is available"})

@app.route('/geolocate_fire', methods=['GET'])
def process_call():
    try:
        logging.info('Received GET request with data: ' + str(request.args))

        request_lat = request.args.get('lat')
        request_lon = request.args.get('lon')

        if request_lat is None or request_lon is None:
            logging.error('Latitude or longitude parameter is missing')
            return jsonify({"error": "Missing latitude or longitude parameter"}), 400

        try:
            request_lat = float(request_lat)
            request_lon = float(request_lon)
        except ValueError as e:
            logging.error(f"Invalid latitude or longitude value: {e}")
            return jsonify({"error": "Invalid latitude or longitude value"}), 400

        random_flag = request.args.get('random', True)
        logging.info('Responding with random mock data')

        if random_flag:
            random_file = random.choice(list(loaded_files.values()))
            if random_file is not None:
                logging.info('Responding with random mock data')
                return jsonify(random_file)
            else:
                logging.warning('No random mock data available')
                return jsonify({"error": "No random mock data available"}), 500

        response = None

        for name in loaded_files:
            file_lat, file_lon = extract_coordinates(name)

            if (file_lat == request_lat) and (file_lon == request_lon):
                response = loaded_files[name]
                break

        if response is None:
            logging.warning('No matching coordinates found in mock data for the request.')
            return jsonify({"error": "No mocked data matches coordinates"}), 400

        logging.info('Responding with mock data')
        return jsonify(response)

    except Exception as e:
        logging.error(f"Error processing request: {e}")
        return jsonify({"error": "Internal server error"}), 500

@app.route('/stopServer', methods=['GET'])
def stop_server():
    logging.info('Server shutdown request received')
    return jsonify({"success": True, "message": "Server has been shut down"})

if __name__ == "__main__":
    app.run(debug=True, port=5001)
