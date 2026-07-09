# amazon-search

Search Amazon for items and recommend 3 by price band and sales.

- On Amazon, search men's light sunscreen shirts; recommend 3 by price band and sales.
- On Amazon, search 500ml insulated thermoses under $30 with sales > 1000.

## Tools
- launch("com.amazon.mShop.android.shopping")
- tap / type — search bar + query
- swipe — scroll the results
- snapshot — read each item card (title, price, sales)

## Selection logic
1. Collect first 20 visible results
2. Filter by: valid price band, sales >= 1000
3. Sort by (price asc, sales desc)
4. Return top 3 with: title, price, sales, shop name
