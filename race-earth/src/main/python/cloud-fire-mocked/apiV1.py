from flask import Flask, jsonify, request, Response
import json
import logging
import random
import os

app = Flask(__name__)

# Load mocked data (all perim files)
files_in_folder = os.listdir('mockedCloudFireData')

# loaded files
loaded_files = {}

for file_name in files_in_folder:
  file_path = os.path.join('mockedCloudFireData', file_name)

  # Check if the path is a file (not a subdirectory)
  if os.path.isfile(file_path):
    # Open file
    with open(file_path) as file:
      # Load content from the JSON file
      file_content = json.load(file)
      loaded_files[file_name] = file_content



# Set up logging
logging.basicConfig(filename='mock_api.log', level=logging.INFO, format='%(asctime)s:%(levelname)s:%(message)s')


# Function to extract coordinates from file names
def extract_coordinates(name):
  lat_start = name.find('lat=') + 4
  lat_end = name.find('_lon=')
  lon_start = name.find('lon=') + 4
  lon_end = name.find('.json')
  lat = float(name[lat_start:lat_end])
  lon = float(name[lon_start:lon_end])
  return lat, lon

# Execute FlaskAPI
@app.route('/process', methods=['GET', 'POST'])
def process_call():
  if request.method == 'GET':
    logging.info('Received test GET request')
    return Response(status=200)

  try:
    logging.info('Received POST request with data: ' + str(request.json))

    # Select a response from mock_data that matches the coordinates in the request

    # Extract lat and lon from request
    request_lat = request.json.get('lat')
    request_lon = request.json.get('lon')

    # Default Response
    response = None

    for name in loaded_files: # For every loaded file (key = name)
      # Get File Coordinates
      file_lat, file_lon = extract_coordinates(name)

      # If file coordinates and request coordinates match
      if (file_lat == request_lat) and (file_lon == request_lon):
        response = loaded_files[name]
        break

    if response is None:
      # Case where no matching data is found for the coordinates
      logging.warning('No matching coordinates found in mock data for the request.')
      return jsonify({"error": "No mocked data matches coordinates"}), 200

    logging.info('Responding with mock data: ' + str(response))
    return jsonify(response)

  except Exception as e:
    logging.error(f"Error processing request: {e}")
    return jsonify({"error": "Internal server error"}), 500

# Stop Server
@app.route('/stopServer', methods=['GET'])
def stop_server():
  logging.info('Server shutdown request received')
  return jsonify({"success": True, "message": "Server has been shut down"})

if __name__ == "__main__":
  app.run(debug=True, port=5001)