"""
README for FireVoice Testing Suite
===================================

This file contains the testing suite for the FireVoice Emergency Call Processor Flask application.

Pre-requisites:
---------------
1. Ensure Python is installed on your system.
2. Install the required libraries:
   - Flask
   - Any other libraries required by the FireVoice application.
3. Ensure the FireVoice application (apiV1.py) is configured properly:
   - Set up the config.json file with the necessary API keys and dispatch location.
   - Ensure all endpoints and functionalities are correctly implemented in apiV1.py.
4. Place any audio files to be used for testing in the appropriate directory and update the file paths in the test cases if necessary.

Running the Tests:
------------------
1. Navigate to the directory containing this script (test_app.py) and the FireVoice application (apiV1.py).
2. Run the test suite using the following command:
    python -m unittest test_app.py

3. Review the output in the console to see the results of the test cases.
- 'OK' indicates all tests passed.
- 'FAIL' or 'ERROR' indicates a test did not pass, and the output will provide more details.

Additional Notes:
-----------------
- The test suite is designed to cover various scenarios, including missing parameters, invalid file paths, and server functionality.
- Ensure the FireVoice application is not running before starting the test suite, as the suite includes tests for starting and stopping the server.

"""

import json
import unittest
import os
from apiV1 import app, fv  # Assuming 'fv' (FireVoice instance) is defined in your apiV1.py file
from subprocess import Popen, PIPE

class FireVoiceTestCase(unittest.TestCase):
    def setUp(self):
        self.app = app.test_client()
        self.app.testing = True

    def test_01_get_process(self):
        response = self.app.get('/process')
        self.assertEqual(response.status_code, 200, msg="GET /process should return 200 OK")

    def test_02_post_process_missing_filepath(self):
        response = self.app.post('/process', content_type='multipart/form-data')
        self.assertEqual(response.status_code, 400, msg="POST /process without 'filepath' should return 400 Bad Request")
        data = json.loads(response.data)
        self.assertIn('Missing filepath', data.get('description', ''), msg="Error message should indicate missing filepath")

    def test_03_post_process_invalid_filepath(self):
        data = {'filepath': 'non_existent_file.mp3'}
        response = self.app.post('/process', data=data, content_type='multipart/form-data')
        self.assertEqual(response.status_code, 500, msg="POST /process with invalid 'filepath' should return 500 Internal Server Error")
        data = json.loads(response.data)
        self.assertIn('Processing failed', data.get('description', ''), msg="Error message should indicate processing failure")

    def test_04_stop_server(self):
        response = self.app.get('/stopServer')
        self.assertEqual(response.status_code, 200, msg="GET /stopServer should return 200 OK")
        data = json.loads(response.data)
        self.assertTrue(data.get('success', False), msg="Server shutdown response should indicate success")

        # Alternatively, you can start and stop the server before and after each test case
        # if you want to isolate the tests even further.

    def test_05_restart_server(self):
        # Assume that your Flask app can be started with a command like: `python apiV1.py`
        command = ["python", "apiV1.py"]
        self.process = Popen(command, stdout=PIPE, stderr=PIPE, universal_newlines=True)

    @classmethod
    def tearDownClass(cls):
        if hasattr(cls, 'process'):
            cls.process.terminate()

if __name__ == '__main__':
    print('starting tests')
    unittest.main()