. as $data
| $data.customer
| map(
    . as $customer
    | ($data.purchase | map(select(.customer_id == $customer.id))) as $orders
    | {
        customer: $customer.name,
        order_count: ($orders | length),
        total: (($orders | map(.amount) | add) // 0)
      }
  )
