define({
    /* This software is in the public domain under CC0 1.0 Universal plus a Grant of Patent License. */
    data: function() { return { notificationCount:0, messageCount:0, eventCount:0, taskCount:0, updateInterval:null, updateErrors:0 } },
    template:
    '<div class="my-account-nav btn-group-condensed">' +
        '<m-link href="/apps/my/User/Task/MyTasks" data-toggle="tooltip" data-container="body" data-original-title="Tareas Abiertas" data-placement="bottom" class="btn btn-default btn-sm navbar-btn navbar-right">' +
            '<i class="fa fa-check"></i> <span class="label label-default">{{taskCount}}</span></m-link>' +
        '<m-link href="/apps/my/User/Calendar/MyCalendar" data-toggle="tooltip" data-container="body" data-original-title="Eventos Esta Semana" data-placement="bottom" class="btn btn-default btn-sm navbar-btn navbar-right">' +
            '<i class="fa fa-calendar"></i> <span class="label label-default">{{eventCount}}</span></m-link>' +
        '<m-link href="/apps/my/User/Messages/FindMessage?statusId=CeReceived&toCurrentUser=true" data-toggle="tooltip" data-container="body" data-original-title="Mensajes" data-placement="bottom" class="btn btn-default btn-sm navbar-btn navbar-right">' +
            '<i class="fa fa-envelope"></i> <span class="label label-default">{{messageCount}}</span></m-link>' +
        '<m-link href="/apps/my/User/Notifications" data-toggle="tooltip" data-container="body" data-original-title="Notificaciones" data-placement="bottom" class="btn btn-default btn-sm navbar-btn navbar-right" :class="{ \'btn-warning\':notificationCount, \'btn-default\':!notificationCount }">' +
            '<i class="fa fa-info-circle"></i> <span class="label" :class="{ \'label-warning\':notificationCount, \'label-default\':!notificationCount }">{{notificationCount}}</span></m-link>' +
    '</div>',
    methods: {
        updateCounts: function() {
            var lastNavDiff = Date.now() - this.$root.lastNavTime;
            // NOTE DEJ 20201104 use 10 hour timeout instead of 1 hour, windows/tabs left open should stay logged in for a generous length working day
            if (this.updateInterval && lastNavDiff > (10*60*60*1000)) {
                console.log('No nav in ' + lastNavDiff + 'ms clearing updateCounts interval');
                clearInterval(this.updateInterval); this.updateInterval = null;
                return;
            }

            var vm = this; $.ajax({ type:'GET', url:(this.$root.appRootPath + '/rest/s1/mantle/my/noticeCounts'),
                dataType:'json', headers:{Accept:'application/json'},
                success: function(countObj) { if (countObj) {
                    if (countObj.notificationCount) vm.notificationCount = countObj.notificationCount;
                    if (countObj.messageCount) vm.messageCount = countObj.messageCount;
                    if (countObj.eventCount) vm.eventCount = countObj.eventCount;
                    if (countObj.taskCount) vm.taskCount = countObj.taskCount;
                    vm.updateErrors = 0;
                }},
                error: function(jqXHR, textStatus, errorThrown) {
                    vm.updateErrors++;
                    console.log('updateCounts ' + textStatus + ' (' + jqXHR.status + '), message ' + errorThrown + ', ' + vm.updateErrors + '/5 errors so far, interval id ' + vm.updateInterval);
                    if (vm.updateErrors > 4 && vm.updateInterval) { console.log('updateCounts clearing interval, too many errors');
                        clearInterval(vm.updateInterval); vm.updateInterval = null; }
                }
            });
        },
        notificationListener: function(jsonObj, webSocket) {
            // TODO: improve this to look for new message, event, and task notifications and increment their counters (or others to decrement...)
            if (jsonObj && jsonObj.persistOnSend === true) this.notificationCount++;
        }
    },
    mounted: function() {
        this.updateCounts();
        this.updateInterval = setInterval(this.updateCounts, 5*60*1000); /* update every 5 minutes, not generally looked at frequently */
        $('.my-account-nav [data-toggle="tooltip"]').tooltip({ placement:'bottom', trigger:'hover' });
        this.$root.notificationClient.registerListener("ALL", this.notificationListener);
    }
});
