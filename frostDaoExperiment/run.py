import subprocess
import time
processes = []
for i in range(35):
    command = "build\\install\\frostDaoExperiment\\bin\\frostDaoExperiment.bat"
    p = subprocess.Popen([command], stderr=subprocess.PIPE)
    #""" , stdout=subprocess.PIPE, stderr=subprocess.PIPE """
    processes.append(p)

while True:
    time.sleep(10000)


