import sys
import time
import os
import shutil
import logging
import threading
import queue
from pathlib import Path
import psutil
from watchdog.observers import Observer
from watchdog.events import FileSystemEventHandler
from PIL import Image

# Configuration
WATCH_DIR = os.path.expanduser('~/Desktop')
PROJECT_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '../../'))
TARGET_DIR = os.path.join(PROJECT_ROOT, 'screenshots')
MAX_WIDTH = 640
MAX_HEIGHT = 416
QUALITY = 85

logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(message)s', datefmt='%Y-%m-%d %H:%M:%S')

# Thread-safe queue for file processing
file_queue = queue.Queue()

def is_minecraft_running():
    for proc in psutil.process_iter(['name', 'cmdline']):
        try:
            if proc.info['name'] == 'java' and proc.info['cmdline']:
                cmd = ' '.join(proc.info['cmdline']).lower()
                if 'minecraft' in cmd or 'fabric' in cmd or 'forge' in cmd or 'lwjgl' in cmd:
                    return True
            if 'minecraft' in proc.info['name'].lower() or 'prism' in proc.info['name'].lower():
                return True
        except (psutil.NoSuchProcess, psutil.AccessDenied, psutil.ZombieProcess):
            pass
    return False

def wait_for_file_ready(filepath, timeout=5):
    start_time = time.time()
    last_size = -1
    while time.time() - start_time < timeout:
        try:
            if not os.path.exists(filepath):
                return False
            size = os.path.getsize(filepath)
            if size > 0 and size == last_size:
                return True
            last_size = size
            time.sleep(0.5)
        except OSError:
            time.sleep(0.1)
    return False

def process_worker():
    while True:
        try:
            filepath = file_queue.get()
            if filepath is None:
                break
            
            filename = os.path.basename(filepath)
            logging.info(f'Worker picked up: {filename}')

            if not is_minecraft_running():
                logging.info(f'Minecraft NOT running. Skipping {filename}')
                file_queue.task_done()
                continue

            if not wait_for_file_ready(filepath):
                logging.warning(f'File not ready or vanished: {filepath}')
                file_queue.task_done()
                continue

            try:
                if not os.path.exists(TARGET_DIR):
                    os.makedirs(TARGET_DIR)
                
                with Image.open(filepath) as img:
                    width, height = img.size
                    if width > MAX_WIDTH or height > MAX_HEIGHT:
                        ratio = min(MAX_WIDTH / width, MAX_HEIGHT / height)
                        new_width = int(width * ratio)
                        new_height = int(height * ratio)
                        img = img.resize((new_width, new_height), Image.Resampling.LANCZOS)
                        logging.info(f'Resized {filename} from {width}x{height} to {new_width}x{new_height}')
                    
                    target_path = os.path.join(TARGET_DIR, filename)
                    base, ext = os.path.splitext(target_path)
                    counter = 1
                    while os.path.exists(target_path):
                        target_path = f'{base}_{counter}{ext}'
                        counter += 1
                    img.save(target_path, optimize=True, quality=QUALITY)
                    logging.info(f'Saved: {target_path}')

                if os.path.exists(filepath):
                    os.remove(filepath)
                    logging.info(f'Removed original: {filepath}')
            except Exception as e:
                logging.error(f'Error processing {filename}: {e}')
            
            file_queue.task_done()
        except Exception as e:
            logging.error(f'Worker crash: {e}')

class ScreenshotHandler(FileSystemEventHandler):
    def on_created(self, event):
        if event.is_directory:
            return
        self._queue_if_screenshot(event.src_path)

    def on_moved(self, event):
        if event.is_directory:
            return
        self._queue_if_screenshot(event.dest_path)

    def _queue_if_screenshot(self, path):
        filename = os.path.basename(path)
        if filename.startswith('Screenshot') and filename.endswith('.png'):
            logging.info(f'Detected: {filename}')
            file_queue.put(path)

if __name__ == '__main__':
    if not os.path.exists(WATCH_DIR):
        logging.error(f'Watch directory does not exist: {WATCH_DIR}')
        sys.exit(1)

    logging.info('Starting Minecraft Screenshot Manager (Threaded)')
    logging.info(f'Watching: {WATCH_DIR}')
    logging.info(f'Target: {TARGET_DIR}')

    worker = threading.Thread(target=process_worker, daemon=True)
    worker.start()

    event_handler = ScreenshotHandler()
    observer = Observer()
    observer.schedule(event_handler, WATCH_DIR, recursive=False)
    observer.start()

    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        observer.stop()
        logging.info('Stopping...')
    
    observer.join()
