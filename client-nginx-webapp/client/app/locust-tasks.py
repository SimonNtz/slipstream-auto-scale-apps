from locust import HttpLocust, TaskSet, task

class UserTasks(TaskSet):
    #@task(1)
    #def get_index(self):
    #    self.locust.client.get("/")
    @task(1)
    def get_load(self):
        self.locust.client.get("/load")

class WebsiteUser(HttpLocust):
    task_set = UserTasks
    min_wait=1000
    max_wait=1000
