import { definePlugin } from "@halo-dev/ui-shared";
import { IconSettings } from "@halo-dev/components";
import { markRaw } from "vue";
import "uno.css";

export default definePlugin({
  components: {},
  routes: [
    {
      parentName: "Root",
      route: {
        path: "/astrahub",
        name: "AstraHub",
        component: () => import("./views/AstraHubView.vue"),
        meta: {
          title: "AstraHub 星链",
          searchable: true,
          permissions: ["plugin:astrahub:manage"],
          menu: {
            name: "AstraHub 星链",
            group: "tool",
            icon: markRaw(IconSettings),
            priority: 0,
            permissions: ["plugin:astrahub:manage"],
          },
        },
      },
    },
  ],
  ucRoutes: [],
  extensionPoints: {},
});
